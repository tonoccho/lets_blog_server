package com.letsblog.publishing.dto;

/**
 * カテゴリ/タグ比較テーブルの1セル分の値。
 * available=false かつ error=false は、その環境スロットにサイトが紐付いていない・
 * 自動構築サイトでもREST/SSHが設定されたサイトでもないことを表す(比較・diff表示の対象外)。
 * available=false かつ error=true は、環境自体は対象だが取得を試みて失敗したことを表す
 * (「対象外」と区別してエラー表示する。errorMessageに詳細を入れる)。
 * available=trueだがslug=nullは、環境自体は比較対象だがこの項目が存在しないことを表す
 * (マスターとの差分として赤字表示の対象になりうる)。
 */
public record TermEnvironmentValue(
        boolean available,
        boolean error,
        String errorMessage,
        String slug,
        String parentSlug,
        String description
) {
    public static TermEnvironmentValue unavailable() {
        return new TermEnvironmentValue(false, false, null, null, null, null);
    }

    public static TermEnvironmentValue error(String errorMessage) {
        return new TermEnvironmentValue(false, true, errorMessage, null, null, null);
    }

    public static TermEnvironmentValue missing() {
        return new TermEnvironmentValue(true, false, null, null, null, null);
    }

    public static TermEnvironmentValue of(String slug, String parentSlug, String description) {
        return new TermEnvironmentValue(true, false, null, slug, parentSlug, description);
    }
}
