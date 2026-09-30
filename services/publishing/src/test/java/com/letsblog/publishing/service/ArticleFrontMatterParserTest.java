package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.publishing.dto.PullRequestArticleResponse.FrontMatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ArticleFrontMatterParser}の単体テスト(issue #1338)。拡張側{@code apps/extension/src/frontMatter.ts}の
 * {@code parseArticle}(gray-matter + normalizeCategoryKey + stripLegacyWordPressIdKeys)と挙動を揃える。
 */
class ArticleFrontMatterParserTest {

    @Test
    @DisplayName("front matterの全項目と本文を取り出す")
    void parsesAllFields() {
        String text = """
                ---
                title: 取得サンプル
                slug: fetch-sample
                status: draft
                categories:
                  - news
                  - tech
                tags: [a, b]
                featured_image: assets/cover.png
                publish_scheduled_at: "2026-12-25T09:00:00Z"
                ---
                # 見出し

                本文です
                """;

        ArticleFrontMatterParser.ParsedArticle parsed = ArticleFrontMatterParser.parse(text);

        assertThat(parsed.frontMatter()).isEqualTo(new FrontMatter(
                "取得サンプル", "fetch-sample", "draft", java.util.List.of("news", "tech"),
                java.util.List.of("a", "b"), "assets/cover.png", "2026-12-25T09:00:00Z"));
        assertThat(parsed.content()).isEqualTo("# 見出し\n\n本文です\n");
    }

    @Test
    @DisplayName("front matterが無ければ全項目null/空で、本文は全体")
    void noFrontMatter() {
        ArticleFrontMatterParser.ParsedArticle parsed = ArticleFrontMatterParser.parse("# だけ\n本文\n");

        assertThat(parsed.frontMatter())
                .isEqualTo(new FrontMatter(null, null, null, java.util.List.of(), java.util.List.of(), null, null));
        assertThat(parsed.content()).isEqualTo("# だけ\n本文\n");
        assertThat(parsed.data()).isEmpty();
    }

    @Test
    @DisplayName("先頭のBOMとCRLFの改行を許容する")
    void bomAndCrlf() {
        String text = "﻿---\r\ntitle: T\r\n---\r\n本文\r\n";

        ArticleFrontMatterParser.ParsedArticle parsed = ArticleFrontMatterParser.parse(text);

        assertThat(parsed.frontMatter().title()).isEqualTo("T");
        assertThat(parsed.content()).isEqualTo("本文\r\n");
    }

    @Test
    @DisplayName("閉じ区切りが無い・先頭が区切りでないときはfront matterとして扱わない")
    void unterminatedOrNotAtStart() {
        assertThat(ArticleFrontMatterParser.parse("---\ntitle: T\n本文").frontMatter().title()).isNull();
        assertThat(ArticleFrontMatterParser.parse("\n---\ntitle: T\n---\n本文").frontMatter().title()).isNull();
    }

    @Test
    @DisplayName("front matterが空(---だけ)なら空のdataになる")
    void emptyFrontMatter() {
        ArticleFrontMatterParser.ParsedArticle parsed = ArticleFrontMatterParser.parse("---\n---\n本文");

        assertThat(parsed.data()).isEmpty();
        assertThat(parsed.content()).isEqualTo("本文");
    }

    @Test
    @DisplayName("単数形categoryはcategoriesへ寄せ、categoryキーは残さない")
    void normalizesCategoryKey() {
        ArticleFrontMatterParser.ParsedArticle parsed =
                ArticleFrontMatterParser.parse("---\ncategory: news\n---\n本文");

        assertThat(parsed.frontMatter().categories()).containsExactly("news");
        assertThat(parsed.data()).doesNotContainKey("category");
    }

    @Test
    @DisplayName("categoryが配列ならそのままcategoriesへ入る")
    void normalizesCategoryArray() {
        ArticleFrontMatterParser.ParsedArticle parsed =
                ArticleFrontMatterParser.parse("---\ncategory: [a, b]\n---\n");

        assertThat(parsed.frontMatter().categories()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("categoriesが非空ならcategoriesを優先しcategoryは捨てる")
    void categoriesWinsOverCategory() {
        ArticleFrontMatterParser.ParsedArticle parsed =
                ArticleFrontMatterParser.parse("---\ncategories: [x]\ncategory: y\n---\n");

        assertThat(parsed.frontMatter().categories()).containsExactly("x");
        assertThat(parsed.data()).doesNotContainKey("category");
    }

    @Test
    @DisplayName("categoriesが空配列ならcategoryで置き換える")
    void emptyCategoriesReplacedByCategory() {
        ArticleFrontMatterParser.ParsedArticle parsed =
                ArticleFrontMatterParser.parse("---\ncategories: []\ncategory: y\n---\n");

        assertThat(parsed.frontMatter().categories()).containsExactly("y");
    }

    @Test
    @DisplayName("categoryがnullなら何も寄せず、categoryキーだけ消える")
    void nullCategory() {
        ArticleFrontMatterParser.ParsedArticle parsed =
                ArticleFrontMatterParser.parse("---\ncategory:\n---\n");

        assertThat(parsed.frontMatter().categories()).isEmpty();
        assertThat(parsed.data()).doesNotContainKey("category");
    }

    @Test
    @DisplayName("廃止されたwp_post_id / wp_post_url / wp_post_idsは取り除く")
    void stripsLegacyWordPressIdKeys() {
        ArticleFrontMatterParser.ParsedArticle parsed = ArticleFrontMatterParser.parse(
                "---\ntitle: T\nwp_post_id: 5\nwp_post_url: http://x\nwp_post_ids: {a: 1}\n---\n");

        assertThat(parsed.data()).containsOnlyKeys("title");
    }

    @Test
    @DisplayName("未知のキーはdataに保持する")
    void keepsUnknownKeys() {
        ArticleFrontMatterParser.ParsedArticle parsed =
                ArticleFrontMatterParser.parse("---\nsite: live\ncustom: 1\n---\n");

        assertThat(parsed.data()).containsEntry("site", "live").containsEntry("custom", 1);
    }

    @Test
    @DisplayName("引用符なしのISO日時(YAMLのtimestamp)もISO 8601文字列で返す")
    void unquotedTimestampBecomesIsoString() {
        ArticleFrontMatterParser.ParsedArticle parsed =
                ArticleFrontMatterParser.parse("---\npublish_scheduled_at: 2026-12-25T09:00:00Z\n---\n");

        assertThat(parsed.frontMatter().publishScheduledAt()).isEqualTo("2026-12-25T09:00:00Z");
    }

    @Test
    @DisplayName("tagsが単一の文字列なら1要素の配列、数値要素は文字列にする")
    void scalarAndNumericLists() {
        ArticleFrontMatterParser.ParsedArticle parsed =
                ArticleFrontMatterParser.parse("---\ntags: one\ncategories: [1, two]\nstatus: 3\n---\n");

        assertThat(parsed.frontMatter().tags()).containsExactly("one");
        assertThat(parsed.frontMatter().categories()).containsExactly("1", "two");
        assertThat(parsed.frontMatter().status()).isEqualTo("3");
    }

    @Test
    @DisplayName("YAMLが壊れているときは原因の分かる例外にする")
    void invalidYaml() {
        assertThatThrownBy(() -> ArticleFrontMatterParser.parse("---\ntitle: [unclosed\n---\n本文"))
                .isInstanceOf(PullRequestArticleException.class)
                .hasMessageContaining("front matter");
    }

    @Test
    @DisplayName("front matterがmappingでない(リスト)ときは例外にする")
    void nonMappingFrontMatter() {
        assertThatThrownBy(() -> ArticleFrontMatterParser.parse("---\n- a\n- b\n---\n本文"))
                .isInstanceOf(PullRequestArticleException.class)
                .hasMessageContaining("front matter");
    }

    @Test
    @DisplayName("categoriesが空文字ならcategoryで置き換え、数値など空でない値のcategoriesはcategoryに負けない")
    void emptyStringAndNonEmptyScalarCategories() {
        assertThat(ArticleFrontMatterParser.parse("---\ncategories: ''\ncategory: y\n---\n")
                .frontMatter().categories()).containsExactly("y");
        assertThat(ArticleFrontMatterParser.parse("---\ncategories: 5\ncategory: y\n---\n")
                .frontMatter().categories()).containsExactly("5");
    }

    @Test
    @DisplayName("リスト中のnull要素は読み飛ばす")
    void nullListElementsAreSkipped() {
        assertThat(ArticleFrontMatterParser.parse("---\ntags: [a, ~, b]\n---\n").frontMatter().tags())
                .containsExactly("a", "b");
    }
}
