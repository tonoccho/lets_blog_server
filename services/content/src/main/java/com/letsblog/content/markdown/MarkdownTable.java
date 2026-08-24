package com.letsblog.content.markdown;

import java.util.List;

/** {@link MarkdownTableParser}の解析結果。rowsの各要素はheadersと同じ列数を持つ。 */
public record MarkdownTable(List<String> headers, List<List<String>> rows) {
}
