package com.letsblog.media.service;

/**
 * 参照画像(issue #1601)として使えない画像が指定された場合の例外。削除済み・他プロジェクトの画像・
 * プロジェクト未指定の要求はいずれもこの例外にまとめる。他プロジェクトの画像IDの存在を
 * 応答の差から探られないようにするため、「無い」と「他プロジェクトの」を区別しない。
 *
 * <p>{@code GlobalExceptionHandler}が400へ写す。ジョブを作る前に投げるので、生成は始まらない。
 */
public class InvalidReferenceImageException extends RuntimeException {
    public InvalidReferenceImageException(String message) {
        super(message);
    }
}
