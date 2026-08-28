package com.letsblog.publishing.dto;

/**
 * スラッグ(大文字小文字を無視した完全一致)で名寄せした、3環境分のカテゴリ/タグ比較行。
 * slugは名寄せキーそのもの(編集・同期・削除の対象を一意に指定するために使う)。
 * nameは表示用の名前(マスター環境の値を優先)で、環境間で表記が異なっていても分裂しない。
 * マスターとの差分判定はフロントエンド側でmasterEnvironmentの列を基準に行う
 * (バックエンドはローデータのみを返す)。
 */
public record TermComparisonRow(
        String name,
        String slug,
        TermEnvironmentValue local,
        TermEnvironmentValue test,
        TermEnvironmentValue production
) {
}
