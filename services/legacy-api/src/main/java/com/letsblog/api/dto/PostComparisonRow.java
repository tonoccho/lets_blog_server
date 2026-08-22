package com.letsblog.api.dto;

/** ポスト/ページ比較行(front matter経由で各環境に同一slugで投稿される前提でslugにより名寄せする)。 */
public record PostComparisonRow(
        String slug,
        PostEnvironmentValue local,
        PostEnvironmentValue test,
        PostEnvironmentValue production
) {
}
