package com.letsblog.api.service;

import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.dto.TagDesignColors;
import com.letsblog.api.markdown.MarkdownRenderer;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * [toc]組み込みタグ(#146、flexmark-ext-tocが解釈する)向けに、プロジェクトごとにカスタマイズされた
 * デザイン(#150)のCSSを注入する。[toc]自体の展開はflexmarkが行う(MarkdownRenderer)ため、
 * このサービスはMarkdown文字列に[toc]が含まれるかだけを判定し、含まれていれば対応するCSSを
 * 本文冒頭に追加する(CustomTagRenderService等と同じ「使われている場合のみ注入」パターン)。
 */
@Service
public class TocStyleRenderService {

    // flexmarkのTocBlockParserが認識する行(大文字小文字を区別しない[toc]、オプション文字列付きも許容)と
    // 同等の判定を行う。実際の展開はflexmark自身が行うため、ここでは「含まれるか」の判定のみで良い。
    private static final Pattern TOC_TAG_PATTERN =
            Pattern.compile("(?im)^\\[toc(?:\\s+[^\\]]*)?]\\s*$");

    private final TagDesignSettingService tagDesignSettingService;

    public TocStyleRenderService(TagDesignSettingService tagDesignSettingService) {
        this.tagDesignSettingService = tagDesignSettingService;
    }

    public String render(String markdown, Long projectId) {
        if (markdown == null || markdown.isEmpty() || !TOC_TAG_PATTERN.matcher(markdown).find()) {
            return markdown;
        }

        TagDesignColors colors = tagDesignSettingService.resolveColors(projectId, EmbedTagType.TOC);
        return "<style>\n" + buildStyle(colors) + "\n</style>\n\n" + markdown;
    }

    /** CustomTagServiceの統合CSS生成からも呼ばれるためpackage-private。 */
    String buildStyle(TagDesignColors colors) {
        String base = "." + MarkdownRenderer.TOC_LIST_CLASS + "{list-style:none;margin:1em 0;padding:12px 16px;"
                + "border-radius:8px;background:" + colors.backgroundColor() + ";}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " ul{list-style:none;}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " li{margin:4px 0;}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " a{color:" + colors.textColor()
                + ";text-decoration:none;}"
                + "." + MarkdownRenderer.TOC_LIST_CLASS + " a:hover{color:" + colors.accentColor()
                + ";text-decoration:underline;}";
        String customCss = colors.customCss();
        return customCss == null || customCss.isBlank() ? base : base + "\n" + customCss.trim();
    }
}
