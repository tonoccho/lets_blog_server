package com.letsblog.media.ai;

import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * COMFYUIプロバイダへ渡すseedの実値を決める(issue #1101)。
 *
 * <p>#1101まで、この決定は{@link ComfyUiClient}の{@code buildWorkflow}が内部で行っていた
 * ({@code System.nanoTime() & 0xFFFFFFFFL})。決めた値はワークフローJSONに埋め込まれるだけで
 * 呼び出し元へ返らないため、seed未指定で生成した画像は{@code generated_images.seed}が
 * NULLのまま保存され、「この設定で画像生成」(#294)・「この画像の設定をコピー」(#437)から
 * 再現できなかった。決定をクライアントの外へ出し、呼び出し側が実値を握れるようにする。
 *
 * <p>値域はComfyUIのKSamplerが受理する範囲に収める。負値は渡さない
 * (負のseedを指定するリクエストは「未指定」と同じくランダム扱いにする。#1101以前の
 * {@code ComfyUiClient}の判定と同じ)。
 */
@Component
public class SeedResolver {

    /**
     * ランダムseedの上限(含む)。#1101以前の{@code System.nanoTime() & 0xFFFFFFFFL}と同じ
     * 非負32bitの範囲を保つ。automatic1111系のUIが扱うseedもこの範囲に収まる。
     */
    static final long MAX_RANDOM_SEED = 0xFFFFFFFFL;

    private final RandomGenerator random;

    /**
     * 乱数源に{@link Random}を使う。{@code java.util.random.RandomGenerator#getDefault()}は
     * 「L32X64MixRandom」という特定アルゴリズムの実装を探すが、それを供給するのはJDK 21では
     * {@code jdk.random}モジュールであり、実行イメージ{@code eclipse-temurin:21-jre}には
     * そのモジュールが無い。{@code @Component}である本クラスの生成が起動時に失敗し、
     * media-service全体がクラッシュループした(#1101 QA)。
     *
     * <p>{@link Random}は{@code java.base}の公開APIで追加モジュールを要さず、
     * スレッドセーフ(シングルトンBeanが並行に引かれても壊れない)。seedは暗号強度を
     * 必要としないため{@code SecureRandom}は過剰、{@code SplittableRandom}は
     * スレッドセーフでなく、{@code ThreadLocalRandom}はフィールドに保持できない。
     */
    public SeedResolver() {
        this(new Random());
    }

    /** テスト専用: 乱数源を差し替えるコンストラクタ。 */
    SeedResolver(RandomGenerator random) {
        this.random = random;
    }

    /**
     * 実際に使うseedを決める。{@code requested}が非nullかつ0以上ならその値を、
     * そうでなければ0以上{@link #MAX_RANDOM_SEED}以下のランダム値を返す。
     */
    public long resolve(Long requested) {
        if (requested != null && requested >= 0) {
            return requested;
        }
        return random.nextLong(MAX_RANDOM_SEED + 1);
    }
}
