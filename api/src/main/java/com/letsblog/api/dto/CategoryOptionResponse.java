package com.letsblog.api.dto;

/**
 * 一括管理のカテゴリ選択UI(親カテゴリ選択・編集/削除対象選択)向けに、参照環境(プロジェクトに
 * 紐づくmanaged環境のうちlocal→test→production優先順で最初に見つかったもの)のカテゴリ一覧を返す。
 */
public record CategoryOptionResponse(
        String name,
        String slug,
        String parentSlug,
        String description
) {
}
