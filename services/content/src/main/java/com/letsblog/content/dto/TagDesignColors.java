package com.letsblog.content.dto;

/**
 * [toc]/[blogcard]/[amazon] レンダリング時に使う確定済みの3色(#RRGGBB形式)と、
 * 任意の追加CSS(customCss、未設定時はnull)。legacy-apiのTagDesignColorsと同じ形。
 * tag_design_settingsテーブル自体はlegacy-apiに残るドメインのため、
 * {@link com.letsblog.content.client.LegacyApiBridgeClient}が内部ブリッジ経由で取得する。
 */
public record TagDesignColors(String backgroundColor, String textColor, String accentColor, String customCss) {
}
