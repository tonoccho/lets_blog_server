package com.letsblog.api.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * GFM形式のシンプルなMarkdownテーブル(ヘッダー行 + 区切り行(`|---|---|`) + データ行)を解析する。
 * [recharts]組み込みタグ(Issue #340)がチャートのデータソースとして使う。
 *
 * セル内のインラインMarkdown書式(太字・リンク等)は解釈せず、生のテキストとしてそのまま扱う
 * (チャートのラベル・数値として使うだけのため、書式を解釈する必要がない)。
 * 入れ子のテーブルや複雑な配置指定(`:---:`等)の意味付けも行わず、区切り行かどうかの判定にのみ使う。
 */
public final class MarkdownTableParser {

    private static final Pattern SEPARATOR_ROW_PATTERN =
            Pattern.compile("^\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?$");
    private static final Pattern UNESCAPED_PIPE = Pattern.compile("(?<!\\\\)\\|");

    private MarkdownTableParser() {
    }

    /**
     * @throws MarkdownTableParseException ヘッダー行/区切り行が無い、区切り行の書式が不正、
     *                                      データ行の列数がヘッダーと一致しない、データ行が0件、のいずれか
     */
    public static MarkdownTable parse(String text) {
        List<String> lines = text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        if (lines.size() < 2) {
            throw new MarkdownTableParseException(
                    "テーブル形式が不正です: ヘッダー行と区切り行(例: |---|---|)が必要です");
        }

        List<String> headers = splitRow(lines.get(0));
        if (!SEPARATOR_ROW_PATTERN.matcher(lines.get(1)).matches()) {
            throw new MarkdownTableParseException(
                    "テーブル形式が不正です: 2行目に区切り行(例: |---|---|)が見つかりません");
        }

        List<List<String>> rows = new ArrayList<>();
        for (int i = 2; i < lines.size(); i++) {
            List<String> cells = splitRow(lines.get(i));
            if (cells.size() != headers.size()) {
                throw new MarkdownTableParseException(
                        "行" + (i + 1) + ": カラム数が見出し(" + headers.size() + "列)と一致しません("
                                + cells.size() + "列)");
            }
            rows.add(cells);
        }
        if (rows.isEmpty()) {
            throw new MarkdownTableParseException("テーブルにデータ行がありません");
        }
        return new MarkdownTable(headers, rows);
    }

    private static List<String> splitRow(String line) {
        String trimmed = line;
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = UNESCAPED_PIPE.split(trimmed, -1);
        List<String> cells = new ArrayList<>(parts.length);
        for (String part : parts) {
            cells.add(part.trim().replace("\\|", "|"));
        }
        return cells;
    }
}
