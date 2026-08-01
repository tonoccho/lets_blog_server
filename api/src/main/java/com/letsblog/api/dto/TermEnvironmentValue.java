package com.letsblog.api.dto;

/**
 * カテゴリ/タグ比較テーブルの1セル分の値。
 * available=falseは、その環境スロットにサイトが紐付いていない・自動構築サイトでないことを表す
 * (この場合slug等はnull、比較・diff表示の対象外として扱う)。
 * available=trueだがslug=nullは、環境自体は比較対象だがこの項目が存在しないことを表す
 * (マスターとの差分として赤字表示の対象になりうる)。
 */
public record TermEnvironmentValue(
        boolean available,
        String slug,
        String parentSlug,
        String description
) {
    public static TermEnvironmentValue unavailable() {
        return new TermEnvironmentValue(false, null, null, null);
    }

    public static TermEnvironmentValue missing() {
        return new TermEnvironmentValue(true, null, null, null);
    }

    public static TermEnvironmentValue of(String slug, String parentSlug, String description) {
        return new TermEnvironmentValue(true, slug, parentSlug, description);
    }
}
