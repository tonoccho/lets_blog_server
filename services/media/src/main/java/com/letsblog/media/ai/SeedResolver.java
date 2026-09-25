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
     * seedの上限(含む)。#1101以前の{@code System.nanoTime() & 0xFFFFFFFFL}と同じ
     * 非負32bitの範囲を保つ。automatic1111系のUIが扱うseedもこの範囲に収まる。
     *
     * <p>ランダム値だけでなく、明示指定されたseedと{@code +repeatIndex}の結果にも
     * この上限を適用する({@link #resolve(Long, int)})。リクエスト側の
     * {@code AiImageRequest.seed}にも同じ上限を{@code @Max}として置いてあるので、
     * ここへ届く値は既に範囲内だが、{@code +repeatIndex}で上端を越える場合だけは
     * ここで巻き戻す。
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
        return resolve(requested, 0);
    }

    /**
     * batch countのリピート{@code repeatIndex}回目(0起点)で使うseedを決める(issue #1102)。
     *
     * <p>seedが指定されている場合は{@code requested + repeatIndex}を返す。automatic1111の
     * batch countと同じで、1回目は指定値そのもの、2回目は+1、…となる。同じ指定を再度投げれば
     * 同じ並びの画像が得られる(再現性が壊れない)。
     *
     * <p>seedが未指定(または負値)の場合はリピートごとに新しいランダム値を引く。
     * 「候補を何枚も見たい」ためのbatch countで同じseedを繰り返しても意味が無いためである。
     *
     * <p><b>戻り値は常に0以上{@link #MAX_RANDOM_SEED}以下</b>である。加算が上端を越える
     * ときは0へ巻き戻す。#1102の当初実装はここを素の加算にしたうえで、
     * 「{@code Long.MAX_VALUE}近傍であふれた値はComfyUIのKSamplerに拒否されるので
     * そのリピートだけが失敗する」とJavadocに<b>事実として</b>書いていたが、それは
     * 検証していない主張だった(#1102 レビュー指摘)。ComfyUIが負のseedをどう扱うかに
     * 依存しないよう、あふれない形にして値域の保証を維持する。
     * リクエストの{@code seed}にも同じ上限を{@code @Max}で置き、入口で弾いている。
     */
    public long resolve(Long requested, int repeatIndex) {
        if (requested != null && requested >= 0) {
            return (requested + repeatIndex) % (MAX_RANDOM_SEED + 1);
        }
        return random.nextLong(MAX_RANDOM_SEED + 1);
    }
}
