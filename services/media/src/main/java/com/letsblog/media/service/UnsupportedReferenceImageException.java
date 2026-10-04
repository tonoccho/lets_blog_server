package com.letsblog.media.service;

/**
 * 選択中の画像生成プロバイダが参照画像付き生成(img2img)に対応していない場合の例外(issue #1601)。
 * 現時点ではChatGPTが該当する(対応は#1602)。
 *
 * <p>{@code GlobalExceptionHandler}が400へ写す。ジョブを作る前に投げるので、生成は始まらない。
 */
public class UnsupportedReferenceImageException extends RuntimeException {
    public UnsupportedReferenceImageException(String message) {
        super(message);
    }
}
