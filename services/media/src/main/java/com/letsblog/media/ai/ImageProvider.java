package com.letsblog.media.ai;

/**
 * 画像生成に使う外部AI(issue #531)。COMFYUIは自前ホスト型のtxt2imgワークフロー、
 * CHATGPTはOpenAIの画像生成API(gpt-image-1固定)を使う。
 */
public enum ImageProvider {
    /** 自前ホストのComfyUI。EmptyLatentImageのbatch_sizeで1回の投入から複数枚を作る。 */
    COMFYUI(16),
    /**
     * OpenAIの画像生成API(gpt-image-1)。1リクエストの{@code n}は最大10のため、
     * {@code batchSize}の上限もそこに合わせる(issue #1102)。11枚以上を受けた場合は
     * 呼び出す前に断る(呼んでもOpenAI側が400を返すだけで、待ち時間と枠だけを消費する)。
     */
    CHATGPT(10);

    private final int maxBatchSize;

    ImageProvider(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * このプロバイダが1回の生成で作れる枚数の上限(issue #1102)。
     * {@code AiImageRequest}の{@code @Max(16)}は全プロバイダ共通の上限で、
     * プロバイダが実行時に決まってからこの値で改めて判定する。
     */
    public int maxBatchSize() {
        return maxBatchSize;
    }

    /** 未設定(null/空文字)はnullを返し、呼び出し側で「既定値を使う」の判定に使えるようにする。 */
    public static ImageProvider fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ImageProvider.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不明な画像生成プロバイダーです: " + value, e);
        }
    }
}
