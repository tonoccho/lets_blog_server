package com.letsblog.publishing.dto;

/**
 * プラグイン・テーマ共通の比較行(slugの完全一致で名寄せする、カテゴリ/タグと異なり表記ゆれはない)。
 */
public record StatusComparisonRow(
        String slug,
        StatusEnvironmentValue local,
        StatusEnvironmentValue test,
        StatusEnvironmentValue production
) {
}
