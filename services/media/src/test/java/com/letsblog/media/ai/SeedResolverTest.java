package com.letsblog.media.ai;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COMFYUIへ渡すseedの実値を決めるリゾルバの検証(issue #1101)。
 *
 * <p>#1101まで、seedの実値は{@link ComfyUiClient}の{@code buildWorkflow}が
 * {@code System.nanoTime() & 0xFFFFFFFFL}で内部的に決めており、その値は
 * ワークフローJSONに埋め込まれるだけで呼び出し元へ返らなかった。結果として
 * seed未指定で生成した画像は{@code generated_images.seed}がNULLになり、再現できなかった。
 * 決定を呼び出し側へ引き上げるための最小の部品がこのクラスである。
 */
@DisplayName("media-service: 画像生成seedの実値決定(issue #1101)")
class SeedResolverTest {

    @Test
    void 指定されたseedはそのまま使う() {
        assertEquals(1234567L, new SeedResolver().resolve(1234567L));
    }

    @Test
    void seedに0を指定してもランダムに置き換えない() {
        assertEquals(0L, new SeedResolver().resolve(0L));
    }

    @Test
    void seed未指定なら0以上のランダム値を返す() {
        SeedResolver resolver = new SeedResolver();
        for (int i = 0; i < 100; i++) {
            long seed = resolver.resolve(null);
            assertTrue(seed >= 0, "seedは0以上でなければならない: " + seed);
        }
    }

    @Test
    void 負のseedはランダム値に置き換える() {
        RandomGenerator fixed = new FixedRandom(42L);
        assertEquals(42L, new SeedResolver(fixed).resolve(-1L));
    }

    @Test
    void seed未指定のときは呼び出しごとに異なる値になりうる() {
        SeedResolver resolver = new SeedResolver();
        long first = resolver.resolve(null);
        boolean differs = false;
        for (int i = 0; i < 100 && !differs; i++) {
            differs = resolver.resolve(null) != first;
        }
        assertTrue(differs, "100回引いて一度も違う値にならないのはランダムではない");
    }

    /**
     * #1101のQAが実機で検出した起動クラッシュの再発防止(RED)。
     *
     * <p>{@code RandomGenerator.getDefault()}は「L32X64MixRandom」という特定の
     * アルゴリズム名で実装を探すが、その実装をどのモジュールが供給するかは
     * ランタイム構成に依存する。JDK 21では{@code jdk.random}モジュールが供給し、
     * 実行イメージ{@code eclipse-temurin:21-jre}にはそのモジュールが無い。
     * その結果{@code @Component}であるSeedResolverの生成が
     * {@code IllegalArgumentException: No implementation of the random number generator
     * algorithm "L32X64MixRandom" is available}で失敗し、media-service全体が
     * 起動しなくなった。
     *
     * <p>ホストのJDK(Temurin 25)ではL32X64MixRandomが{@code java.base}に移動しており、
     * 「起動するか」「モジュール名がjava.baseか」を見るテストでは欠陥を再現できない。
     * そこでクラスファイルを直接読み、{@code getDefault}への参照そのものが無いことを
     * 固定する。ランタイム構成に左右されない唯一の検証。
     */
    @Test
    void 既定の乱数源はRandomGenerator_getDefaultに依存しない() throws Exception {
        String constantPool;
        try (InputStream in = SeedResolver.class.getResourceAsStream("SeedResolver.class")) {
            assertNotNull(in, "SeedResolver.classを読めない");
            constantPool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        assertFalse(
                constantPool.contains("getDefault"),
                "SeedResolverがRandomGenerator.getDefault()を参照している。"
                        + "その実装はjava.base外のモジュール(JDK 21ではjdk.random)が供給するため、"
                        + "eclipse-temurin:21-jreでは生成に失敗しmedia-serviceが起動しない(#1101 QA)");
    }

    /**
     * 既定の乱数源が、ランタイム構成に依存しないjava.baseの公開APIであること。
     *
     * <p>{@code jdk.internal.random.*}や{@code jdk.random}由来の実装は、どのJDK・
     * どの実行イメージでも存在するとは限らない。java.baseが公開している
     * {@code java.util.*}の実装だけを使う。
     */
    @Test
    void 既定の乱数源はjava_baseの公開APIである() throws Exception {
        Field field = SeedResolver.class.getDeclaredField("random");
        field.setAccessible(true);
        RandomGenerator random = (RandomGenerator) field.get(new SeedResolver());

        Class<?> implementation = random.getClass();
        assertEquals(
                "java.base",
                implementation.getModule().getName(),
                "乱数実装は java.base が供給しなければならない: " + implementation.getName());
        assertTrue(
                implementation.getName().startsWith("java."),
                "乱数実装はjava.baseの公開API(java.*)でなければならない。"
                        + "内部実装クラスはランタイム構成に依存する: " + implementation.getName());
    }

    /** {@code nextLong(bound)}だけを固定値に差し替える最小のRandomGenerator。 */
    private record FixedRandom(long value) implements RandomGenerator {
        @Override
        public long nextLong() {
            return value;
        }

        @Override
        public long nextLong(long bound) {
            return value;
        }
    }
}
