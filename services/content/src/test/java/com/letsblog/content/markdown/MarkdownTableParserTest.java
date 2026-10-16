package com.letsblog.content.markdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** [recharts] のデータソースになる Markdown テーブルの解析。純粋な関数なので実物へ文字列を渡す。 */
class MarkdownTableParserTest {

    @Test
    void 外側のパイプのある標準的な表を読む() {
        MarkdownTable table = MarkdownTableParser.parse("| 月 | 売上 |\n|---|---|\n| 1月 | 10 |\n| 2月 | 20 |");

        assertThat(table.headers()).containsExactly("月", "売上");
        assertThat(table.rows()).containsExactly(List.of("1月", "10"), List.of("2月", "20"));
    }

    @Test
    void 外側のパイプが無い表も読む() {
        MarkdownTable table = MarkdownTableParser.parse("月 | 売上\n--- | ---\n1月 | 10");

        assertThat(table.headers()).containsExactly("月", "売上");
        assertThat(table.rows()).containsExactly(List.of("1月", "10"));
    }

    @Test
    void 行末のエスケープされたパイプは区切りではなく_セルの末尾の文字として扱う() {
        MarkdownTable table = MarkdownTableParser.parse("| a | b |\n|---|---|\n| x | y \\|");

        assertThat(table.rows()).containsExactly(List.of("x", "y |"));
    }

    @Test
    void セル内のエスケープされたパイプは文字として残す() {
        MarkdownTable table = MarkdownTableParser.parse("| a | b |\n|---|---|\n| x \\| z | y |");

        assertThat(table.rows()).containsExactly(List.of("x | z", "y"));
    }

    @Test
    void 空行は読み飛ばす() {
        MarkdownTable table = MarkdownTableParser.parse("\n| a |\n\n|---|\n\n| 1 |\n");

        assertThat(table.headers()).containsExactly("a");
        assertThat(table.rows()).containsExactly(List.of("1"));
    }

    @Test
    void 行が2行に満たなければ拒否する() {
        assertThatThrownBy(() -> MarkdownTableParser.parse("| a | b |"))
                .isInstanceOf(MarkdownTableParseException.class)
                .hasMessageContaining("ヘッダー行と区切り行");
        assertThatThrownBy(() -> MarkdownTableParser.parse("  \n "))
                .isInstanceOf(MarkdownTableParseException.class);
    }

    @Test
    void 二行目が区切り行でなければ拒否する() {
        assertThatThrownBy(() -> MarkdownTableParser.parse("| a | b |\n| 1 | 2 |\n| 3 | 4 |"))
                .isInstanceOf(MarkdownTableParseException.class)
                .hasMessageContaining("区切り行");
    }

    @Test
    void データ行の列数が見出しと違えば行番号つきで拒否する() {
        assertThatThrownBy(() -> MarkdownTableParser.parse("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 |"))
                .isInstanceOf(MarkdownTableParseException.class)
                .hasMessageContaining("行4")
                .hasMessageContaining("2列")
                .hasMessageContaining("1列");
    }

    @Test
    void データ行が無ければ拒否する() {
        assertThatThrownBy(() -> MarkdownTableParser.parse("| a | b |\n|---|---|"))
                .isInstanceOf(MarkdownTableParseException.class)
                .hasMessageContaining("データ行がありません");
    }
}
