package com.letsblog.platform.service;

import com.letsblog.platform.service.VscodeExtensionBuildService.BuiltExtension;
import com.letsblog.platform.service.VscodeExtensionBuildService.VscodeExtensionBuildException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * issue #1190 症状B(504 Gateway Timeout)の回帰テスト。
 *
 * <p>{@code buildLock}はビルド全体を直列化するだけで、後続リクエストは
 * 「先行の全ビルド時間 + 自分のビルド時間」を待たされていた
 * (2026-09-19のリリース検証・2026-09-22のレビューで確認済み)。修正後は、
 * 進行中のビルドがあるとき後続をそのビルドに<b>合流</b>させ、同じ成果物を共有する。
 *
 * <p>実際のnpm/vsceは呼ばず、{@link VscodeExtensionBuildService#build(Path, String)}
 * (package-private)を差し替え可能な{@link FakeBuildService}で置き換えて検証する。
 */
class VscodeExtensionBuildServiceConcurrentAccessTest {

    @TempDir
    Path tempDir;

    private Path sourceDir;
    private Path buildDir;

    private FakeBuildService service(long buildDurationMillis) throws IOException {
        return service(buildDurationMillis, FakeBuildService.Mode.SUCCESS);
    }

    private FakeBuildService service(long buildDurationMillis, FakeBuildService.Mode mode) throws IOException {
        sourceDir = tempDir.resolve("source");
        buildDir = tempDir.resolve("build");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("package.json"),
                "{\"name\":\"letsblog-vscode\",\"version\":\"9.9.9\"}");
        return new FakeBuildService(sourceDir.toString(), buildDir.toString(), buildDurationMillis, mode);
    }

    /**
     * 進行中のビルドがあるときに合流することを、実ビルド呼び出し回数と応答時間の両面で確認する。
     * 合流していなければ、5並列で実ビルドが5回走り、応答時間もおよそ5倍かかるはず。
     */
    @Test
    void 複数リクエストが同時に来ても実ビルドは1回だけ_応答時間も1回分で済む() throws Exception {
        long buildDurationMillis = 300;
        FakeBuildService service = service(buildDurationMillis);
        int concurrency = 5;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BuiltExtension>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                return service.buildAndGetVsix();
            }));
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();

        List<BuiltExtension> results = new ArrayList<>();
        for (Future<BuiltExtension> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();

        assertEquals(1, service.buildCallCount.get(),
                concurrency + "並列でも進行中のビルドに合流するはずなので、実ビルド呼び出しは1回だけのはず"
                        + "(実際には" + service.buildCallCount.get() + "回)");

        Path firstDir = results.get(0).requestOutputDir();
        for (BuiltExtension result : results) {
            assertEquals(firstDir, result.requestOutputDir(), "合流していれば全員が同じ出力先を受け取るはず");
        }

        assertTrue(elapsedMillis < buildDurationMillis * 2,
                "応答時間(" + elapsedMillis + "ms)が1回分のビルド時間(" + buildDurationMillis
                        + "ms)の2倍以上かかっている。「先行の全ビルド + 自分のビルド」を待たされている疑いがある");
    }

    /**
     * 合流している場合、共有した出力先ディレクトリは「最後の読み手が読み終わるまで」
     * 削除してはいけない。既存の{@code CleanupOnCloseResource}は1リクエスト1ディレクトリを
     * 前提にしているため、合流を入れても壊れていないことをここで守る。
     */
    @Test
    void 合流した全員が読み終えるまで共有した出力先ディレクトリは削除されない() throws Exception {
        long buildDurationMillis = 300;
        FakeBuildService service = service(buildDurationMillis);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BuiltExtension>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                return service.buildAndGetVsix();
            }));
        }
        ready.await();
        go.countDown();
        BuiltExtension first = futures.get(0).get(10, TimeUnit.SECONDS);
        BuiltExtension second = futures.get(1).get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(first.requestOutputDir(), second.requestOutputDir(),
                "合流していれば同じ出力先ディレクトリを受け取るはず");
        assertTrue(Files.exists(first.requestOutputDir()));

        service.cleanupAfterDownload(first);
        assertTrue(Files.exists(first.requestOutputDir()),
                "もう一方の合流者がまだ読み終えていないのに出力先ディレクトリが削除された"
                        + "(先に読み終えたリクエストが他の合流者の分も削除する、症状Aと同種の事故)");

        service.cleanupAfterDownload(second);
        assertFalse(Files.exists(first.requestOutputDir()),
                "合流した全員が読み終えたのに出力先ディレクトリが削除されずに残った(ディスクを圧迫する)");
    }

    /**
     * ビルドが完了して進行中のビルドが無くなった後に届いたリクエストは、
     * 古い成果物に合流せず新規にビルドし直す。issue #696の「キャッシュしない」方針の回帰防止。
     */
    @Test
    void ビルド完了後の次のリクエストは新しいビルドを行い古い成果物に合流しない() throws Exception {
        FakeBuildService service = service(10);

        BuiltExtension first = service.buildAndGetVsix();
        BuiltExtension second = service.buildAndGetVsix();

        assertEquals(2, service.buildCallCount.get(), "逐次リクエストはそれぞれ新規にビルドされるはず");
        assertNotEquals(first.requestOutputDir(), second.requestOutputDir());
    }

    /** ビルド自体は成功として扱われても、出力ファイルが無ければ例外を投げる(既存の防御)。 */
    @Test
    void ビルドが成功しても出力ファイルが無ければ例外を投げる() throws Exception {
        FakeBuildService service = service(0, FakeBuildService.Mode.MISSING_OUTPUT);

        VscodeExtensionBuildException exception =
                assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);
        assertTrue(exception.getMessage().contains("出力ファイルが見つかりません"), exception.getMessage());
    }

    /**
     * リーダーのビルドが失敗した場合、合流者にも同じ例外が伝播すること(issue #1190、症状B)。
     * 合流者が誰にも配布されない成果物を待ち続けたり、別の例外にすり替わったりしてはいけない。
     */
    @Test
    void リーダーのビルドが失敗すると合流者にも同じ例外が伝播する() throws Exception {
        FakeBuildService service = service(300, FakeBuildService.Mode.THROW_BUILD_EXCEPTION);
        List<Throwable> causes = runConcurrentlyAndCollectFailures(service, 2);

        assertEquals(1, service.buildCallCount.get(), "合流していれば実ビルドは1回だけ試みられるはず");
        for (Throwable cause : causes) {
            assertTrue(cause instanceof VscodeExtensionBuildException,
                    "合流者にもリーダーと同じ例外型が伝播するはず: " + cause);
        }
    }

    /**
     * リーダーのビルドが{@link VscodeExtensionBuildException}以外を投げた場合、リーダー自身は
     * 元の例外をそのまま受け取るが、合流者は{@link VscodeExtensionBuildException}でラップされて
     * 受け取る(issue #1190、症状B)。
     */
    @Test
    void リーダーのビルドが未知の例外を投げても合流者はVscodeExtensionBuildExceptionでラップされて受け取る()
            throws Exception {
        FakeBuildService service = service(300, FakeBuildService.Mode.THROW_GENERIC_EXCEPTION);
        List<Throwable> causes = runConcurrentlyAndCollectFailures(service, 2);

        assertEquals(1, service.buildCallCount.get(), "合流していれば実ビルドは1回だけ試みられるはず");
        long rawCount = causes.stream().filter(c -> c instanceof IllegalStateException).count();
        long wrappedCount = causes.stream().filter(c -> c instanceof VscodeExtensionBuildException).count();
        assertEquals(1, rawCount, "リーダー自身は元の例外をそのまま受け取るはず: " + causes);
        assertEquals(1, wrappedCount, "合流者はVscodeExtensionBuildExceptionでラップされて受け取るはず: " + causes);

        IllegalStateException original = (IllegalStateException) causes.stream()
                .filter(c -> c instanceof IllegalStateException).findFirst().orElseThrow();
        VscodeExtensionBuildException wrapped = (VscodeExtensionBuildException) causes.stream()
                .filter(c -> c instanceof VscodeExtensionBuildException).findFirst().orElseThrow();
        assertEquals(original, wrapped.getCause(), "ラップした例外のcauseは元の例外と同じはず");
    }

    /**
     * {@code concurrency}並列で{@link VscodeExtensionBuildService#buildAndGetVsix()}を呼び、
     * 全員が例外で終わることを確認したうえで、それぞれの例外の原因({@link ExecutionException#getCause()}相当)
     * を集めて返す。
     */
    private static List<Throwable> runConcurrentlyAndCollectFailures(FakeBuildService service, int concurrency)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BuiltExtension>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                return service.buildAndGetVsix();
            }));
        }
        ready.await();
        go.countDown();

        List<Throwable> causes = new ArrayList<>();
        for (Future<BuiltExtension> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
                fail("ビルド失敗時は例外が伝播するはず");
            } catch (ExecutionException e) {
                causes.add(e.getCause());
            } catch (java.util.concurrent.TimeoutException e) {
                fail("応答がタイムアウトした: " + e);
            }
        }
        pool.shutdown();
        return causes;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 実際のnpm ci/npm run compile/npx vsce packageを呼ばずに、指定した時間だけ
     * かかる「ビルド」を模擬する。{@link VscodeExtensionBuildService#build}は
     * このテストのために合流の検証ができるよう差し替え可能にした(package-private、非final)。
     */
    private static final class FakeBuildService extends VscodeExtensionBuildService {

        /** 模擬ビルドの結末。 */
        enum Mode {
            /** 出力ファイルを書いて正常終了する。 */
            SUCCESS,
            /** 例外を投げずに終わるが、出力ファイルを書かない(#1190レビュー指摘の防御分岐の検証用)。 */
            MISSING_OUTPUT,
            /** {@link VscodeExtensionBuildException}(既知の例外型)を投げる。 */
            THROW_BUILD_EXCEPTION,
            /** {@link VscodeExtensionBuildException}以外の例外を投げる(ラップ経路の検証用)。 */
            THROW_GENERIC_EXCEPTION
        }

        private final AtomicInteger buildCallCount = new AtomicInteger(0);
        private final long buildDurationMillis;
        private final Mode mode;

        FakeBuildService(String sourceDir, String buildDir, long buildDurationMillis, Mode mode) {
            super(sourceDir, buildDir);
            this.buildDurationMillis = buildDurationMillis;
            this.mode = mode;
        }

        @Override
        void build(Path outputDir, String filename) {
            buildCallCount.incrementAndGet();
            try {
                Files.createDirectories(outputDir);
                Thread.sleep(buildDurationMillis);
                switch (mode) {
                    case MISSING_OUTPUT -> { /* 意図的に出力ファイルを書かない */ }
                    case THROW_BUILD_EXCEPTION ->
                            throw new VscodeExtensionBuildException("模擬ビルド失敗(既知の例外型)");
                    case THROW_GENERIC_EXCEPTION ->
                            throw new IllegalStateException("模擬ビルド失敗(未知の例外型)");
                    default -> Files.writeString(outputDir.resolve(filename), "dummy-vsix-content");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VscodeExtensionBuildException("模擬ビルドが中断されました", e);
            } catch (IOException e) {
                throw new VscodeExtensionBuildException("模擬ビルドの準備に失敗しました: " + e.getMessage(), e);
            }
        }
    }
}
