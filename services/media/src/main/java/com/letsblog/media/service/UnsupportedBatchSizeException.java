package com.letsblog.media.service;

/**
 * 選択中の画像生成プロバイダが1回に作れる枚数を超えるbatchSizeを要求された場合の例外(issue #1102)。
 *
 * <p>{@code AiImageRequest}のBean Validationでは表現できない。実効上限は
 * {@link com.letsblog.media.ai.ImageProvider}ごとに違い(COMFYUI=16、CHATGPT=10)、
 * どのプロバイダを使うかはプロジェクト設定から<b>実行時に</b>決まるためである。
 *
 * <p>{@code GlobalExceptionHandler}が400へ写す。プロバイダを呼ぶ前に投げるので、
 * 生成は始まらず{@code generated_images}にも行は増えない。
 */
public class UnsupportedBatchSizeException extends RuntimeException {
    public UnsupportedBatchSizeException(String message) {
        super(message);
    }
}
