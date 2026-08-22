package com.letsblog.api.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTableParserTest {

    @Test
    void parse_ヘッダーと区切り行とデータ行を解析する() {
        String text = """
                | month | revenue |
                |-------|---------|
                | 2024-01 | 100000 |
                | 2024-02 | 120000 |
                """;

        MarkdownTable table = MarkdownTableParser.parse(text);

        assertEquals(List.of("month", "revenue"), table.headers());
        assertEquals(List.of(
                List.of("2024-01", "100000"),
                List.of("2024-02", "120000")
        ), table.rows());
    }

    @Test
    void parse_先頭末尾のパイプが無くても解析できる() {
        String text = """
                month | revenue
                ------|--------
                2024-01 | 100000
                """;

        MarkdownTable table = MarkdownTableParser.parse(text);

        assertEquals(List.of("month", "revenue"), table.headers());
        assertEquals(List.of(List.of("2024-01", "100000")), table.rows());
    }

    @Test
    void parse_区切り行のコロン揃えは無視して区切り行として扱う() {
        String text = """
                | name | value |
                |:-----|------:|
                | a | 1 |
                """;

        MarkdownTable table = MarkdownTableParser.parse(text);

        assertEquals(List.of("name", "value"), table.headers());
    }

    @Test
    void parse_行が2行未満の場合はエラー() {
        MarkdownTableParseException e = assertThrows(MarkdownTableParseException.class,
                () -> MarkdownTableParser.parse("| month | revenue |"));
        assertTrue(e.getMessage().contains("区切り行"));
    }

    @Test
    void parse_区切り行の書式が不正な場合はエラー() {
        String text = """
                | month | revenue |
                | 2024-01 | 100000 |
                """;

        MarkdownTableParseException e = assertThrows(MarkdownTableParseException.class,
                () -> MarkdownTableParser.parse(text));
        assertTrue(e.getMessage().contains("区切り行"));
    }

    @Test
    void parse_データ行のカラム数が見出しと一致しない場合はエラー() {
        String text = """
                | month | revenue |
                |-------|---------|
                | 2024-01 | 100000 | 余分 |
                """;

        MarkdownTableParseException e = assertThrows(MarkdownTableParseException.class,
                () -> MarkdownTableParser.parse(text));
        assertTrue(e.getMessage().contains("カラム数"));
        assertTrue(e.getMessage().contains("行3"));
    }

    @Test
    void parse_データ行が0件の場合はエラー() {
        String text = """
                | month | revenue |
                |-------|---------|
                """;

        MarkdownTableParseException e = assertThrows(MarkdownTableParseException.class,
                () -> MarkdownTableParser.parse(text));
        assertTrue(e.getMessage().contains("データ行"));
    }

    @Test
    void parse_セル内のエスケープされたパイプは列区切りとして扱わない() {
        String text = """
                | name | note |
                |------|------|
                | a | x\\|y |
                """;

        MarkdownTable table = MarkdownTableParser.parse(text);

        assertEquals(List.of("a", "x|y"), table.rows().get(0));
    }
}
