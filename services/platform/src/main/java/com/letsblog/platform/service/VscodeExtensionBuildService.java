package com.letsblog.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * システム画面からのVSCode拡張機能ダウンロード用に、.vsixパッケージをオンデマンドでビルドする。
 * ソースは読み取り専用でマウントされた{@code vscode-extension-source-path}配下にあり、
 * 書き込み可能な作業ディレクトリへコピーした上で npm ci → npm run compile → npx vsce package を実行する
 * (ホストコマンドにProcessBuilderで委譲するパターン)。
 * ダウンロードのたびに必ず再ビルドする(以前はバージョンごとにキャッシュしていたが、
 * package.jsonのversionを上げ忘れると古いビルドが配布され続けてしまう事故が起きたため、
 * キャッシュはせず常に最新ソースからビルドする)。
 *
 * <p>legacy-apiから移設したもの(issue #696、C10-4。#579で分割されたplatform-service抽出の
 * 最終split。#693/#694/#695(C10-1〜C10-3)と同じくlegacy-apiの実装をそのまま踏襲しており、
 * ビルド手順・キャッシュしない方針に変更はない)。
 */
@Service
@Slf4j
public class VscodeExtensionBuildService {

    private static final int TIMEOUT_SECONDS = 300;
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private final Path sourceDir;
    private final Path buildDir;

    /**
     * {@link #currentBuild}の読み書きを守るモニタ(issue #1190、症状B)。
     * 「進行中のビルドが1つも無ければ新規に始め、あれば合流する」という判定と
     * {@link #currentBuild}への代入をアトミックに行うために使う。
     */
    private final Object buildMonitor = new Object();

    /** 現在進行中のビルド(無ければ{@code null})。{@link #buildMonitor}の下でのみ読み書きする。 */
    private InFlightBuild currentBuild;

    /**
     * 出力先ディレクトリごとの残り読み手数(issue #1190、症状B)。合流によって複数の
     * リクエストが同じ{@link BuiltExtension#requestOutputDir()}を共有しうるため、
     * 「最後の読み手が読み終わるまで削除しない」ための参照カウントに使う。
     * {@link #buildAndGetVsix()}がリーダー・合流者を問わず呼び出しのたびに1つ登録し、
     * {@link #cleanupAfterDownload}が呼ばれるたびに1つ減らして0になったら物理削除する。
     * ここに登録が無いパス(このメソッドを経由せず{@link BuiltExtension}を直接組み立てて
     * 呼ばれた場合。既存の単体テストが行っている)は、従来どおり1回の呼び出しで即削除する。
     */
    private final Map<Path, AtomicInteger> pendingCleanupCounts = new ConcurrentHashMap<>();

    public VscodeExtensionBuildService(
            @Value("${app.vscode-extension-source-path}") String sourceDir,
            @Value("${app.vscode-extension-build-path}") String buildDir) {
        this.sourceDir = Path.of(sourceDir);
        this.buildDir = Path.of(buildDir);
    }

    /**
     * @param requestOutputDir このビルド専用の出力先ディレクトリ({@link #allocateRequestOutputDir}で
     *                         割り当てられたもの)。ダウンロード完了後は{@link #cleanupAfterDownload}で
     *                         削除する。合流(issue #1190、症状B)している場合は複数の呼び出しが
     *                         同じ{@code requestOutputDir}を受け取りうる。
     */
    public record BuiltExtension(Path vsixPath, String filename, Path requestOutputDir) {
    }

    /**
     * 進行中のビルド1つを表す。出力先・ファイル名はリーダー(このビルドを実際に走らせる
     * スレッド)がビルド開始前に確定させ、以後合流する側はこれをそのまま受け取る。
     * {@link #future}が完了すると、合流した側もリーダーと同じ結果(または同じ例外)を
     * 受け取れる(issue #1190、症状B)。
     */
    private static final class InFlightBuild {
        private final Path requestOutputDir;
        private final String filename;
        private final Path vsixPath;
        private final CompletableFuture<BuiltExtension> future = new CompletableFuture<>();

        private InFlightBuild(Path requestOutputDir, String filename) {
            this.requestOutputDir = requestOutputDir;
            this.filename = filename;
            this.vsixPath = requestOutputDir.resolve(filename);
        }
    }

    /**
     * 常に最新ソースから再ビルドして.vsixのパスを返す(キャッシュしない)。
     *
     * <p><b>症状A(issue #1190)</b>: 出力先はリクエストごとに一意なディレクトリにする。
     * 以前はバージョン番号のみの固定パスを使っていたため、あるリクエストがレスポンス
     * 送出中に別リクエストが同じファイルを{@code Files.deleteIfExists}で削除してしまい
     * 500になっていた。
     *
     * <p><b>症状B(issue #1190)</b>: {@code buildLock}でビルド全体を直列化するだけでは、
     * 後続リクエストは「先行の全ビルド時間 + 自分のビルド時間」を待たされ、gatewayの
     * 応答タイムアウトを超えて504になっていた(2026-09-19のリリース検証で実測。1回の
     * ビルドが47〜53秒かかる環境で2並列が直列に走ると100秒近くに達する)。そこで、
     * 進行中のビルドが既にあるときは新しいビルドを起動せず、その進行中のビルドに
     * <b>合流</b>して同じ成果物を共有する。合流した側も、リーダーが読み取ったのと
     * <b>同じ時刻のソース</b>から作られた成果物を受け取る(合流はあくまで「今まさに
     * 走っているビルド」に相乗りするだけで、過去の完了済みビルドの結果を使い回す
     * ものではない)ため、「キャッシュしない(毎回最新ソースからビルドする)」方針
     * (issue #696)には反しない。ビルドが完了して進行中のビルドが無くなった後に届いた
     * リクエストは、合流せず新規にビルドし直す。
     *
     * <p>共有する成果物は<b>最後の読み手が読み終わるまで削除してはいけない</b>
     * ({@link #pendingCleanupCounts}で参照カウントする。{@link #cleanupAfterDownload}
     * 参照)。既存の{@code CleanupOnCloseResource}(コントローラ側)は1リクエスト1
     * ディレクトリを前提にしているため、ここで参照カウントを設けずに共有すると、
     * 先に読み終えたリクエストが他の合流者の分もろとも削除してしまい、症状Aと同じ
     * 「読んでいる最中に消される」事故を合流という別の経路で再発させることになる。
     *
     * <p>ビルド自体が失敗した場合(このメソッドが例外を投げる場合)は、割り当てた
     * 出力先ディレクトリの後片付けは行わない(既知の限界。issue #1378で追跡)。
     */
    public BuiltExtension buildAndGetVsix() {
        String version = readVersion();
        String filename = "letsblog-vscode-" + version + ".vsix";

        InFlightBuild build;
        boolean isLeader;
        synchronized (buildMonitor) {
            if (currentBuild != null) {
                build = currentBuild;
                isLeader = false;
            } else {
                Path outputRoot = buildDir.resolve("output");
                Path requestOutputDir = allocateRequestOutputDir(outputRoot);
                build = new InFlightBuild(requestOutputDir, filename);
                currentBuild = build;
                isLeader = true;
            }
            // リーダー・合流者を問わず、この成果物を受け取る「読み手」として登録する。
            // buildMonitorの下で行うため、登録とcurrentBuildの切り替え(下のfinally節)は
            // 競合しない(issue #1190、症状B)。
            pendingCleanupCounts.computeIfAbsent(build.requestOutputDir, dir -> new AtomicInteger(0))
                    .incrementAndGet();
        }

        if (!isLeader) {
            return awaitJoinedBuild(build);
        }

        boolean failed = false;
        try {
            build(build.requestOutputDir, build.filename);
            if (!Files.exists(build.vsixPath)) {
                throw new VscodeExtensionBuildException("ビルドは成功しましたが出力ファイルが見つかりません: " + build.vsixPath);
            }
            BuiltExtension result = new BuiltExtension(build.vsixPath, build.filename, build.requestOutputDir);
            build.future.complete(result);
            return result;
        } catch (RuntimeException e) {
            failed = true;
            build.future.completeExceptionally(e);
            throw e;
        } finally {
            // pendingCleanupCountsの後片付けとcurrentBuildの解除を同じbuildMonitorの下で
            // アトミックに行う(issue #1190、レビュー指摘のTOCTOU修正)。
            //
            // 以前はpendingCleanupCounts.remove(...)をcatchブロック(buildMonitorの外)で
            // 行っていたため、「削除は完了したがcurrentBuildはまだ失敗したビルドを指したまま」
            // という窓が生じ、そこに届いた合流者がcomputeIfAbsentで新しいエントリを作って
            // しまっていた。その合流者はfuture.get()が即座に例外を投げるためBuiltExtensionを
            // 受け取れず、cleanupAfterDownloadを永久に呼ばない(=誰にも減算されないエントリが
            // プロセス終了まで残る)。
            //
            // 削除とcurrentBuild解除を同じ臨界区間にすると、合流者の「currentBuildを見て
            // 合流を決め、pendingCleanupCountsへ登録する」処理(このメソッド冒頭の
            // synchronized(buildMonitor)ブロック)と完全に排他になる。したがって合流者は
            // 必ず次のいずれかになる:
            //   (a) この臨界区間より前に登録できていた場合 → 削除はそのエントリ(登録済みの
            //       カウント)ごと正しく破棄する(失敗したビルドの成果物は誰にも配布されない
            //       ため、カウントの値に関わらず捨ててよい)。
            //   (b) この臨界区間より後に来た場合 → currentBuildは既にnullなので合流せず、
            //       新しいリーダーとして新しい出力先ディレクトリを割り当てる(古い
            //       ディレクトリには一切触れない)。
            // どちらの経路でも、失敗したビルドの出力先に対する参照カウントの新規エントリが
            // 誤って作られることは無い。
            synchronized (buildMonitor) {
                if (failed) {
                    pendingCleanupCounts.remove(build.requestOutputDir);
                }
                currentBuild = null;
            }
        }
    }

    /**
     * 進行中のビルドに合流し、その完了(または失敗)を待つ(issue #1190、症状B)。
     */
    private BuiltExtension awaitJoinedBuild(InFlightBuild build) {
        try {
            return build.future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VscodeExtensionBuildException("進行中のビルドへの合流待ちに失敗しました: " + e.getMessage(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof VscodeExtensionBuildException buildException) {
                throw buildException;
            }
            throw new VscodeExtensionBuildException("合流したビルドが失敗しました: " + cause, cause);
        }
    }

    /**
     * リクエストごとに一意な出力先ディレクトリを割り当てる(issue #1190)。
     * 他のリクエストの出力と衝突しないよう{@link UUID}をディレクトリ名に使う。
     */
    Path allocateRequestOutputDir(Path outputRoot) {
        return outputRoot.resolve(UUID.randomUUID().toString());
    }

    /**
     * ダウンロード(レスポンス本文の送出)完了後に呼ばれる。合流(issue #1190、症状B)に
     * よって複数のリクエストが同じ出力先ディレクトリを共有している場合があるため、
     * 即座には削除せず、{@link #pendingCleanupCounts}の参照カウントが0になった
     * (=登録されていた読み手全員が読み終えた)ときにだけ物理削除する。
     *
     * <p>{@link #pendingCleanupCounts}に登録が無い場合(このメソッドを経由せず
     * {@link BuiltExtension}を直接組み立てて呼ばれた場合。既存の単体テストが行っている)
     * は、従来どおり1回の呼び出しで即削除する。
     */
    public void cleanupAfterDownload(BuiltExtension built) {
        Path dir = built.requestOutputDir();
        AtomicInteger remaining = pendingCleanupCounts.get(dir);
        if (remaining != null) {
            if (remaining.decrementAndGet() > 0) {
                // 他の合流者がまだ読み終えていないため、削除を見送る(issue #1190、症状B)。
                return;
            }
            pendingCleanupCounts.remove(dir);
        }
        deleteRecursively(dir);
    }

    private String readVersion() {
        Path packageJson = sourceDir.resolve("package.json");
        if (!Files.exists(packageJson)) {
            throw new VscodeExtensionBuildException(
                    "VSCode拡張のソースが見つかりません: " + packageJson + " (Docker Composeで起動しているか確認してください)");
        }
        try {
            String content = Files.readString(packageJson);
            Matcher matcher = VERSION_PATTERN.matcher(content);
            if (!matcher.find()) {
                throw new VscodeExtensionBuildException("package.jsonからversionを読み取れませんでした: " + packageJson);
            }
            return matcher.group(1);
        } catch (IOException e) {
            throw new VscodeExtensionBuildException("package.jsonの読み込みに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 実際にnpm ci → npm run compile → npx vsce packageを実行する。パッケージ外(テスト)へは
     * 公開しないが、同一パッケージのテストが合流(issue #1190、症状B)を実ビルド無しで
     * 検証できるよう、あえてprivateにせずオーバーライド可能にしている
     * ({@code VscodeExtensionBuildServiceConcurrentAccessTest}参照)。
     */
    void build(Path outputDir, String filename) {
        Path workspace = buildDir.resolve("workspace");
        try {
            Files.createDirectories(outputDir);
            // vsce packageの--outが確実に新しい内容で上書きするよう、既存の同名ファイルは先に削除しておく
            Files.deleteIfExists(outputDir.resolve(filename));
            deleteRecursively(workspace);
            Files.createDirectories(workspace);
            copySource(sourceDir, workspace);

            runCommand(workspace, "npm", "ci", "--no-audit", "--no-fund");
            runCommand(workspace, "npm", "run", "compile");
            runCommand(workspace, "npx", "vsce", "package", "--allow-missing-repository",
                    "--out", outputDir.resolve(filename).toString());
        } catch (IOException e) {
            throw new VscodeExtensionBuildException("ビルド用ディレクトリの準備に失敗しました: " + e.getMessage(), e);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private void copySource(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String relative = source.relativize(path).toString();
                if (relative.isEmpty() || relative.startsWith("node_modules") || relative.startsWith("out")) {
                    continue;
                }
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void runCommand(Path workDir, String... command) {
        String commandLine = String.join(" ", command);
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) {
                process.destroyForcibly();
                throw new VscodeExtensionBuildException(commandLine + " がタイムアウトしました: " + output);
            }
            if (process.exitValue() != 0) {
                throw new VscodeExtensionBuildException(
                        commandLine + " が失敗しました(exit=" + process.exitValue() + "): " + output);
            }
            log.info("VSCode拡張ビルド: {} 完了", commandLine);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new VscodeExtensionBuildException(commandLine + " の実行に失敗しました: " + e.getMessage(), e);
        }
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    log.warn("VSCode拡張ビルドの一時ファイル削除に失敗しました: {}", path);
                }
            });
        } catch (IOException e) {
            log.warn("VSCode拡張ビルドの一時ディレクトリ削除に失敗しました: {}", dir);
        }
    }

    public static class VscodeExtensionBuildException extends RuntimeException {
        public VscodeExtensionBuildException(String message) {
            super(message);
        }

        public VscodeExtensionBuildException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
