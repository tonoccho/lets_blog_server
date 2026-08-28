package com.letsblog.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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
    private final ReentrantLock buildLock = new ReentrantLock();

    public VscodeExtensionBuildService(
            @Value("${app.vscode-extension-source-path}") String sourceDir,
            @Value("${app.vscode-extension-build-path}") String buildDir) {
        this.sourceDir = Path.of(sourceDir);
        this.buildDir = Path.of(buildDir);
    }

    public record BuiltExtension(Path vsixPath, String filename) {
    }

    /**
     * 常に最新ソースから再ビルドして.vsixのパスを返す(キャッシュしない)。
     * 同時ダウンロードによるビルドの競合はロックで直列化する。
     */
    public BuiltExtension buildAndGetVsix() {
        String version = readVersion();
        String filename = "letsblog-vscode-" + version + ".vsix";
        Path outputDir = buildDir.resolve("output");
        Path builtPath = outputDir.resolve(filename);

        buildLock.lock();
        try {
            build(outputDir, filename);
            if (!Files.exists(builtPath)) {
                throw new VscodeExtensionBuildException("ビルドは成功しましたが出力ファイルが見つかりません: " + builtPath);
            }
            return new BuiltExtension(builtPath, filename);
        } finally {
            buildLock.unlock();
        }
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

    private void build(Path outputDir, String filename) {
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
