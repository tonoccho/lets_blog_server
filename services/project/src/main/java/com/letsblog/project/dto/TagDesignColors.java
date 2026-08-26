package com.letsblog.project.dto;

/**
 * [toc]/[blogcard]/[amazon] レンダリング時に使う確定済みの3色(#RRGGBB形式)と、
 * 任意の追加CSS(customCss、未設定時はnull)。
 */
public record TagDesignColors(String backgroundColor, String textColor, String accentColor, String customCss) {
}
