package com.letsblog.publishing.dto;

/**
 * ポスト/ページ比較テーブルの1セル分の値。available/errorの意味はStatusEnvironmentValueと同じ
 * (available=false&error=false→環境未紐付け、available=false&error=true→取得失敗)。
 * available=trueかつpostIdがnullの場合はその環境にslug一致する投稿/ページが存在しないことを表す。
 */
public record PostEnvironmentValue(
        boolean available,
        boolean error,
        String errorMessage,
        String postId,
        String title,
        String status
) {
    public static PostEnvironmentValue unavailable() {
        return new PostEnvironmentValue(false, false, null, null, null, null);
    }

    public static PostEnvironmentValue error(String errorMessage) {
        return new PostEnvironmentValue(false, true, errorMessage, null, null, null);
    }

    public static PostEnvironmentValue notFound() {
        return new PostEnvironmentValue(true, false, null, null, null, null);
    }

    public static PostEnvironmentValue of(String postId, String title, String status) {
        return new PostEnvironmentValue(true, false, null, postId, title, status);
    }
}
