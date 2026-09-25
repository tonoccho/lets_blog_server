package com.letsblog.platform.service;

import com.letsblog.platform.service.VscodeExtensionBuildService.VscodeExtensionBuildException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * issue #1190 レビュー(ラウンド2)指摘: {@code buildAndGetVsix()}の失敗経路で、
 * {@code pendingCleanupCounts.remove(...)}(catchブロック、buildMonitorの外)と
 * {@code currentBuild = null}(finallyブロック、buildMonitorの中)が別々の臨界区間にある。
 * この隙間に届いたリクエストは「合流」しようとして{@code computeIfAbsent(...)}を呼ぶが、
 * リーダーが既にエントリを削除した直後のため<b>新しいエントリ(count=1)を作ってしまう</b>。
 * その合流者は{@code future.get()}が即座に例外を投げるため{@code BuiltExtension}を
 * 受け取れず、{@code cleanupAfterDownload}を永久に呼ばない。結果、このエントリは
 * プロセスが終わるまで残り続ける(#1378と複合すると共有ディレクトリも永久に残る)。
 *
 * <p>この隙間はナノ秒オーダーで、{@code Thread.sleep}に頼るテストは「祈るテスト」に
 * なってしまう。そこで{@code pendingCleanupCounts}自体をリフレクションで計測用の
 * {@link ConcurrentHashMap}サブクラスへ差し替え、{@code remove(Object)}が
 * <b>実際の削除を完了させた直後・呼び出し元(リーダー)に制御を返す前</b>で確実に
 * 一時停止するようにして、決定的に再現する。
 *
 * <p>修正後(削除とcurrentBuildのリセットを同じ{@code buildMonitor}の下で行う設計)では、
 * この一時停止は{@code buildMonitor}を保持したまま起こる。そのため「窓に届くリクエスト」は
 * 別スレッドで発行し、その完了を待たずに{@code resumeRemoval}を解放できるようにしてある
 * ——そうしないと、修正後のコードに対してこのテスト自身がデッドロックする
 * (合流側は{@code buildMonitor}待ちで進めず、リーダー側は{@code resumeRemoval}待ちで
 * 進めない循環待ちになるため)。
 */
class VscodeExtensionBuildServicePendingCleanupRaceTest {

    @TempDir
    Path tempDir;

    @Test
    void 失敗したビルドの後片付けとcurrentBuildのリセットの間に合流しても参照カウントが漏れない() throws Exception {
        Path sourceDir = tempDir.resolve("source");
        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("package.json"),
                "{\"name\":\"letsblog-vscode\",\"version\":\"9.9.9\"}");

        FailingBuildService service = new FailingBuildService(sourceDir.toString(), buildDir.toString());

        CountDownLatch removalStarted = new CountDownLatch(1);
        CountDownLatch resumeRemoval = new CountDownLatch(1);
        InstrumentedCleanupCounts instrumented = new InstrumentedCleanupCounts(removalStarted, resumeRemoval);
        replacePendingCleanupCounts(service, instrumented);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> leaderOutcome = pool.submit(() -> {
            try {
                service.buildAndGetVsix();
                fail("模擬ビルドは常に失敗するはず");
            } catch (VscodeExtensionBuildException expected) {
                // 期待どおり: リーダー自身も失敗を受け取る
            }
        });

        assertTrue(removalStarted.await(5, TimeUnit.SECONDS),
                "リーダーがpendingCleanupCounts.remove()まで進まなかった(タイムアウト)");

        // ここが本来最も危険な窓: リーダーは削除を完了させたが、currentBuildはまだ
        // 失敗したビルドを指したまま一時停止している(issue #1190レビュー指摘のTOCTOU)。
        // この隙に届くリクエストは「合流」を試みる。修正後の実装では、この一時停止が
        // buildMonitorを保持したまま起こるため、このリクエストを同じスレッドで待って
        // しまうとデッドロックする。そのため別スレッドで発行し、完了を待たずに進める。
        Future<?> joinerOutcome = pool.submit(() -> {
            try {
                service.buildAndGetVsix();
                fail("模擬ビルドは常に失敗するはず");
            } catch (VscodeExtensionBuildException expected) {
                // 期待どおり: 窓に届いたリクエストも(合流であれ新規リーダーであれ)失敗を受け取る
            }
        });

        // 修正前の実装では合流者はロック待ちにならないため、ほぼ即座にcomputeIfAbsentへ
        // 到達する。この待ち合わせは「祈り」ではなく、実際に窓を通り抜けたことを示す
        // シグナル(2件目の登録)を待つもの。修正後の実装では合流者はbuildMonitor待ちで
        // 先に進めずタイムアウトするが、それ自体が「窓が閉じている」ことの確認になる
        // (最終的な正しさはこの後のcontainsKeyで検証するため、ここでの成否は分岐させない)。
        instrumented.secondRegistration.await(500, TimeUnit.MILLISECONDS);

        resumeRemoval.countDown();
        leaderOutcome.get(5, TimeUnit.SECONDS);
        joinerOutcome.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(2, instrumented.registeredKeys.size(),
                "リーダー(初回登録)と、窓に届いたリクエスト(2回目の登録)の合計2回のはず: "
                        + instrumented.registeredKeys);
        Path originalDir = instrumented.registeredKeys.get(0);

        assertFalse(instrumented.containsKey(originalDir),
                "失敗したビルドの出力先(" + originalDir + ")の参照カウントが、窓に届いた"
                        + "リクエストの新規登録によって残留した(issue #1190レビュー指摘のTOCTOU)。"
                        + "このエントリは誰にもdecrementされずプロセス終了まで残る"
                        + "(#1378と複合すると共有ディレクトリも永久に残る)");

        Path secondDir = instrumented.registeredKeys.get(1);
        assertFalse(instrumented.containsKey(secondDir),
                "窓に届いたリクエスト自身の出力先(" + secondDir + ")の参照カウントも"
                        + "残留してはいけない(合流していれば上のoriginalDirと同じ値のはずで二重に、"
                        + "新規リーダーとしてやり直していれば自分自身の失敗経路で片付くはず)");
    }

    private static void replacePendingCleanupCounts(VscodeExtensionBuildService service,
            ConcurrentHashMap<Path, AtomicInteger> replacement) throws ReflectiveOperationException {
        Field field = VscodeExtensionBuildService.class.getDeclaredField("pendingCleanupCounts");
        field.setAccessible(true);
        field.set(service, replacement);
    }

    /** 常に既知の例外型を即座に投げる、実ビルドを使わないフェイク。 */
    private static final class FailingBuildService extends VscodeExtensionBuildService {
        FailingBuildService(String sourceDir, String buildDir) {
            super(sourceDir, buildDir);
        }

        @Override
        void build(Path outputDir, String filename) {
            throw new VscodeExtensionBuildException("模擬ビルド失敗(issue #1190レビュー指摘のTOCTOU再現用)");
        }
    }

    /**
     * {@code pendingCleanupCounts}を差し替えて、{@code remove(Object)}の最初の呼び出しを
     * 「実際の削除が完了した直後・呼び出し元に制御が戻る前」で一時停止させる。
     * {@code computeIfAbsent}に渡されたキーはすべて{@link #registeredKeys}に記録し、
     * 2件目が登録された時点で{@link #secondRegistration}を解放する。
     */
    private static final class InstrumentedCleanupCounts extends ConcurrentHashMap<Path, AtomicInteger> {
        private final CountDownLatch removalStarted;
        private final CountDownLatch resumeRemoval;
        private final CountDownLatch secondRegistration = new CountDownLatch(1);
        private final AtomicInteger removeCalls = new AtomicInteger(0);
        private final List<Path> registeredKeys = Collections.synchronizedList(new ArrayList<>());

        InstrumentedCleanupCounts(CountDownLatch removalStarted, CountDownLatch resumeRemoval) {
            this.removalStarted = removalStarted;
            this.resumeRemoval = resumeRemoval;
        }

        @Override
        public AtomicInteger computeIfAbsent(Path key, Function<? super Path, ? extends AtomicInteger> mappingFunction) {
            registeredKeys.add(key);
            if (registeredKeys.size() == 2) {
                secondRegistration.countDown();
            }
            return super.computeIfAbsent(key, mappingFunction);
        }

        @Override
        public AtomicInteger remove(Object key) {
            AtomicInteger removed = super.remove(key);
            if (removeCalls.getAndIncrement() == 0) {
                removalStarted.countDown();
                awaitQuietly(resumeRemoval);
            }
            return removed;
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
