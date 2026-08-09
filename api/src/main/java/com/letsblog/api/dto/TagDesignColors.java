package com.letsblog.api.dto;

/** [toc]/[blogcard]/[amazon] レンダリング時に使う確定済みの3色(#RRGGBB形式)。 */
public record TagDesignColors(String backgroundColor, String textColor, String accentColor) {
}
