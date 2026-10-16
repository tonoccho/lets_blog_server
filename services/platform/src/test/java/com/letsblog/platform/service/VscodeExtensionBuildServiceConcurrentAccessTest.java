package com.letsblog.platform.service;

import com.letsblog.platform.service.VscodeExtensionBuildService.BuiltExtension;
import com.letsblog.platform.service.VscodeExtensionBuildService.VscodeExtensionBuildException;
import com.letsblog.platform.testsupport.FakeBin;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * <p>issue #1825: npm / npx は{@code src/test/fake-bin}の代役の実行ファイル(npxが指定秒数かかる)で、
 * {@link VscodeExtensionBuildService}の実物(ProcessBuilderでの起動を含む)がそのまま呼ぶ。
 * サービスを継承して{@code build}を差し替えることはしない。実ビルドの回数は、代役の{@code npx}の起動回数で数える。
 */
class VscodeExtensionBuildServiceConcurrentAccessTest {

    @TempDir
    Path tempDir;

    private Path sourceDir;
    private Path buildDir;

    @BeforeEach
    void resetFakeBin() {
        FakeBin.reset();
    }

    private VscodeExtensionBuildService service(long buildDurationMillis) throws IOException {
        sourceDir = tempDir.resolve("source");
        buildDir = tempDir.resolve("build");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("package.json"),
                "{\"name\":\"letsblog-vscode\",\"version\":\"9.9.9\"}");
        FakeBin.npxSleepSeconds(String.valueOf(buildDurationMillis / 1000.0));
        return new VscodeExtensionBuildService(sourceDir.toString(), buildDir.toString());
    }

    private static int buildCalls() {
        return (int) FakeBin.calls().stream().filter(c -> c.get(0).equals("npx")).count();
    }

    /**
     * 進行中のビルドがあるときに合流することを、実ビルド呼び出し回数と応答時間の両面で確認する。
     * 合流していなければ、5並列で実ビルドが5回走り、応答時間もおよそ5倍かかるはず。
     */
    @Test
    void 複数リクエストが同時に来ても実ビルドは1回だけ_応答時間も1回分で済む() throws Exception {
        long buildDurationMillis = 300;
        VscodeExtensionBuildService service = service(buildDurationMillis);
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

        assertEquals(1, buildCalls(),
                concurrency + "並列でも進行中のビルドに合流するはずなので、実ビルド呼び出しは1回だけのはず"
                        + "(実際には" + buildCalls() + "回)");

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
        VscodeExtensionBuildService service = service(buildDurationMillis);

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
        VscodeExtensionBuildService service = service(10);

        BuiltExtension first = service.buildAndGetVsix();
        BuiltExtension second = service.buildAndGetVsix();

        assertEquals(2, buildCalls(), "逐次リクエストはそれぞれ新規にビルドされるはず");
        assertNotEquals(first.requestOutputDir(), second.requestOutputDir());
    }

    /** ビルド自体は成功として扱われても、出力ファイルが無ければ例外を投げる(既存の防御)。 */
    @Test
    void ビルドが成功しても出力ファイルが無ければ例外を投げる() throws Exception {
        VscodeExtensionBuildService service = service(0);
        FakeBin.npxWritesNoOutput();

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
        VscodeExtensionBuildService service = service(300);
        FakeBin.fail("npx", "vsce: packaging failed");
        List<Throwable> causes = runConcurrentlyAndCollectFailures(service, 2);

        assertEquals(1, buildCalls(), "合流していれば実ビルドは1回だけ試みられるはず");
        for (Throwable cause : causes) {
            assertTrue(cause instanceof VscodeExtensionBuildException,
                    "合流者にもリーダーと同じ例外型が伝播するはず: " + cause);
        }
    }

    /**
     * 進行中のビルドが{@link VscodeExtensionBuildException}以外の例外で失敗したとき、合流者は
     * {@link VscodeExtensionBuildException}でラップされて受け取る(issue #1190、症状B)。
     *
     * <p>実ビルドは{@link VscodeExtensionBuildException}しか投げないので、「リーダーが別の型の例外で失敗した」状態を
     * 作るには、進行中のビルド({@code currentBuild})をリフレクションで組み立てて失敗させておく。
     * 合流者の実コード({@code buildAndGetVsix} → {@code awaitJoinedBuild})は実物のまま通す。
     */
    @Test
    void リーダーのビルドが未知の例外で失敗しても合流者はVscodeExtensionBuildExceptionでラップされて受け取る()
            throws Exception {
        VscodeExtensionBuildService service = service(0);
        IllegalStateException original = new IllegalStateException("想定外の失敗");
        installInFlightBuild(service).completeExceptionally(original);

        VscodeExtensionBuildException wrapped =
                assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);

        assertTrue(wrapped.getMessage().contains("合流したビルドが失敗しました"), wrapped.getMessage());
        assertEquals(original, wrapped.getCause(), "ラップした例外のcauseは元の例外と同じはず");
        assertEquals(0, buildCalls(), "合流者は自分では実ビルドしない");
    }

    /** 進行中のビルドが{@link VscodeExtensionBuildException}で失敗していれば、合流者はその例外をそのまま受け取る。 */
    @Test
    void リーダーのビルドが既知の例外で失敗していれば合流者は同じ例外を受け取る() throws Exception {
        VscodeExtensionBuildService service = service(0);
        VscodeExtensionBuildException original = new VscodeExtensionBuildException("npm ci が失敗しました");
        installInFlightBuild(service).completeExceptionally(original);

        VscodeExtensionBuildException received =
                assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);

        assertSame(original, received);
    }

    /** 進行中のビルドの完了を待つ合流者が割り込まれたら、中断の例外にして割り込み状態を戻す。 */
    @Test
    void 合流して待っている最中に割り込まれたら中断の例外にして割り込み状態を戻す() throws Exception {
        VscodeExtensionBuildService service = service(0);
        installInFlightBuild(service); // 完了させない = 合流者はずっと待つ
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread joiner = new Thread(() -> {
            try {
                service.buildAndGetVsix();
            } catch (Throwable t) {
                thrown.set(t);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        joiner.start();
        Thread.sleep(200);
        joiner.interrupt();
        joiner.join(10_000);

        assertFalse(joiner.isAlive());
        assertTrue(thrown.get() instanceof VscodeExtensionBuildException, String.valueOf(thrown.get()));
        assertTrue(thrown.get().getMessage().contains("合流待ちに失敗"), thrown.get().getMessage());
        assertTrue(interrupted.get(), "割り込み状態を戻してから例外を伝える");
    }

    /** 進行中のビルド(InFlightBuild)を組み立てて{@code currentBuild}に据え、その完了待ちのfutureを返す。 */
    @SuppressWarnings("unchecked")
    private java.util.concurrent.CompletableFuture<BuiltExtension> installInFlightBuild(
            VscodeExtensionBuildService service) throws ReflectiveOperationException {
        Class<?> inFlight = Class.forName(VscodeExtensionBuildService.class.getName() + "$InFlightBuild");
        java.lang.reflect.Constructor<?> constructor = inFlight.getDeclaredConstructor(Path.class, String.class);
        constructor.setAccessible(true);
        Object build = constructor.newInstance(buildDir.resolve("output").resolve("in-flight"), "letsblog-vscode-9.9.9.vsix");
        java.lang.reflect.Field future = inFlight.getDeclaredField("future");
        future.setAccessible(true);
        java.lang.reflect.Field current = VscodeExtensionBuildService.class.getDeclaredField("currentBuild");
        current.setAccessible(true);
        current.set(service, build);
        return (java.util.concurrent.CompletableFuture<BuiltExtension>) future.get(build);
    }

    /**
     * {@code concurrency}並列で{@link VscodeExtensionBuildService#buildAndGetVsix()}を呼び、
     * 全員が例外で終わることを確認したうえで、それぞれの例外の原因({@link ExecutionException#getCause()}相当)
     * を集めて返す。
     */
    private static List<Throwable> runConcurrentlyAndCollectFailures(VscodeExtensionBuildService service, int concurrency)
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
}
