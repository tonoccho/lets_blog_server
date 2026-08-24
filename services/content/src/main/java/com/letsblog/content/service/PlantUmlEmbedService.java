package com.letsblog.content.service;

import com.letsblog.content.render.MediaRenderClient;
import org.springframework.stereotype.Service;

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
 */
@Service
public class PlantUmlEmbedService {

    private static final Pattern PLANTUML_BLOCK_PATTERN =
            Pattern.compile("```plantuml\\s*\\n(.*?)```", Pattern.DOTALL);

    private final MediaRenderClient mediaRenderClient;

    public PlantUmlEmbedService(MediaRenderClient mediaRenderClient) {
        this.mediaRenderClient = mediaRenderClient;
    }

    /**
     * プレビュー向けに```plantumlフェンスコードブロックをPNGへレンダリングし、data URIとして
     * 画像参照に差し替える。CMS認証情報を必要とせず、メディアアップロードも行わない
     * (プレビューは副作用のある外部呼び出しを避けるため)。
     */
    public String embedDiagramsForPreview(String markdown) {
        Matcher matcher = PLANTUML_BLOCK_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String diagramSource = matcher.group(1).trim();
            byte[] png = mediaRenderClient.renderPlantUml(wrapWithMarkers(diagramSource));
            String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

            matcher.appendReplacement(result, Matcher.quoteReplacement("![diagram](" + dataUri + ")"));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String wrapWithMarkers(String diagramSource) {
        if (diagramSource.contains("@startuml")) {
            return diagramSource;
        }
        return "@startuml\n" + diagramSource + "\n@enduml";
    }
}
