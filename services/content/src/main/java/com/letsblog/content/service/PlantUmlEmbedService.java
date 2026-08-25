package com.letsblog.content.service;

import com.letsblog.common.client.SyncServiceException;
import com.letsblog.content.render.MediaRenderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown本文中の ```plantuml フェンスコードブロックをPlantUMLサーバー(media-service経由)で
 * PNGにレンダリングする。legacy-apiのPlantUmlEmbedServiceのうち、プレビュー向け(CMS認証情報を
 * 必要としない)embedDiagramsForPreviewのみをcontent-serviceへ移設する(issue #576)。
 *
 * <p>投稿向け(CMSメディアライブラリへのアップロードを伴うembedDiagrams)は、CmsAdapter/
 * CmsCredentials(project-service/publishing-serviceがまだ抽出されていないCMSドメイン)への
 * 深い依存があり、issue #575(publishing-service)の対象のためlegacy-apiに残した(legacy-api側の
 * PlantUmlEmbedServiceはembedDiagramsのみを残す形に縮小している)。
 *
 * <p>フォールバック方針(issue #581、C12): media-serviceのレンダリング呼び出しが失敗しても、
 * プレビュー全体を失敗させない。1図だけプレースホルダ(「diagram unavailable」メッセージ)へ差し替え、
 * 残りの本文のプレビューは継続する(issue #581の受入基準の具体例そのもの。CMS投稿を伴わない
 * プレビュー専用経路のため、副作用が無く安全にプレースホルダで代替できる)。PlantUmlTagRenderService
 * /RechartsTagRenderService(組み込みタグ`[plantuml]`/`[recharts]`)は、タグの記法・データ誤りを
 * 呼び出し元に明示する既存の設計判断(Javadoc参照)を優先し、このプレースホルダ化の対象外とする。
 */
@Service
public class PlantUmlEmbedService {

    private static final Logger log = LoggerFactory.getLogger(PlantUmlEmbedService.class);

    private static final Pattern PLANTUML_BLOCK_PATTERN =
            Pattern.compile("```plantuml\\s*\\n(.*?)```", Pattern.DOTALL);

    private final MediaRenderClient mediaRenderClient;

    public PlantUmlEmbedService(MediaRenderClient mediaRenderClient) {
        this.mediaRenderClient = mediaRenderClient;
    }

    /**
     * プレビュー向けに```plantumlフェンスコードブロックをPNGへレンダリングし、data URIとして
     * 画像参照に差し替える。CMS認証情報を必要とせず、メディアアップロードも行わない
     * (プレビューは副作用のある外部呼び出しを避けるため)。media-serviceへの呼び出しが失敗した
     * 図はプレースホルダへ差し替え、プレビュー全体は失敗させない。
     */
    public String embedDiagramsForPreview(String markdown) {
        Matcher matcher = PLANTUML_BLOCK_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String diagramSource = matcher.group(1).trim();
            String replacement = renderOrPlaceholder(diagramSource);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String renderOrPlaceholder(String diagramSource) {
        try {
            byte[] png = mediaRenderClient.renderPlantUml(wrapWithMarkers(diagramSource));
            String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
            return "![diagram](" + dataUri + ")";
        } catch (SyncServiceException e) {
            log.warn("media-serviceのPlantUMLレンダリングに失敗したため、プレースホルダへ差し替えます: {}", e.getMessage());
            return "<div class=\"lb-diagram-unavailable\">"
                    + "図の生成に失敗しました(diagram unavailable): "
                    + HtmlUtils.htmlEscape(e.getMessage())
                    + "</div>";
        }
    }

    private String wrapWithMarkers(String diagramSource) {
        if (diagramSource.contains("@startuml")) {
            return diagramSource;
        }
        return "@startuml\n" + diagramSource + "\n@enduml";
    }
}
