package com.letsblog.api.ai;

/**
 * 画像生成に使う外部AI(issue #531)。COMFYUIは自前ホスト型のtxt2imgワークフロー、
 * CHATGPTはOpenAIの画像生成API(gpt-image-1固定)を使う。
 */
public enum ImageProvider {
    COMFYUI, CHATGPT;

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
