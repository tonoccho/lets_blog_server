package com.letsblog.api.dto;

/**
 * 名前(大文字小文字を無視した完全一致)で名寄せした、3環境分のカテゴリ/タグ比較行。
 * マスターとの差分判定はフロントエンド側でmasterEnvironmentの列を基準に行う
 * (バックエンドはローデータのみを返す)。
 */
public record TermComparisonRow(
        String name,
        TermEnvironmentValue local,
        TermEnvironmentValue test,
        TermEnvironmentValue production
) {
}
