package com.letsblog.content.markdown;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.toc.TocExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MarkdownをHTMLへ変換する。WordPressの投稿本文(content)にそのまま渡せるHTMLを生成する。
 */
@Component
public class MarkdownRenderer {

    /** 組み込み[toc]タグの対象見出しレベル(H2〜H4)。ビットNが見出しレベルNに対応する(flexmark-ext-tocの仕様)。 */
    private static final int TOC_LEVELS_H2_TO_H4 = (1 << 2) | (1 << 3) | (1 << 4);

    /** TagDesignSettingService経由のデザインカスタマイズ(#150)がCSSで対象にするためのクラス名。 */
    public static final String TOC_LIST_CLASS = "lb-toc-list";

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create(), TocExtension.create()));
        options.set(TocExtension.LEVELS, TOC_LEVELS_H2_TO_H4);
        // 組み込みカスタムタグの規約(小文字 [toc])に合わせ、flexmark標準の[TOC]記法の大文字小文字を区別しない
        options.set(TocExtension.CASE_SENSITIVE_TOC_TAG, false);
        // デザインカスタマイズ(#150)がCSSで目次を対象にできるよう、生成される<ul>にクラスを付与する
        // (DIV_CLASSはtitle未設定時はラップ用<div>自体が生成されないため使えず、LIST_CLASSを使う)
        options.set(TocExtension.LIST_CLASS, TOC_LIST_CLASS);

        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    public String render(String markdown) {
        Node document = parser.parse(markdown == null ? "" : markdown);
        return renderer.render(document);
    }
}
