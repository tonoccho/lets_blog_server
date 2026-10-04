package com.letsblog.publishing.dto;

/**
 * @param url       署名付きプレビューURL。開くと実テーマの単一記事テンプレートで表示される
 * @param expiresAt 期限(epoch秒)
 */
public record SignedPreviewUrlResponse(String url, long expiresAt) {
}
