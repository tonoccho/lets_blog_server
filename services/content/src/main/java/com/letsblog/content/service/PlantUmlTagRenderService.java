package com.letsblog.content.service;

import com.letsblog.content.client.AiServiceException;
import com.letsblog.content.render.MediaRenderClient;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown本文中の組み込みタグ`[plantuml]`〜`[/plantuml]`(本文はPlantUML記法)を画像に展開する(issue #344)。
 * legacy-apiのPlantUmlTagRenderServiceのうち、プレビュー向け(CMS認証情報を必要としない)
 * renderForPreviewのみをcontent-serviceへ移設する(issue #576)。
 *
 * <p>投稿向け(CMSメディアライブラリへのアップロードを伴うrender)は、CmsAdapter/CmsCredentials
 * (project-service/publishing-serviceがまだ抽出されていないCMSドメイン)への深い依存があり、
 * issue #575(publishing-service)の対象のためlegacy-apiに残した(legacy-api側のPlantUmlTagRenderService
 * はrenderのみを残す形に縮小している)。
 */
@Service
public class PlantUmlTagRenderService {

    private static final Pattern PLANTUML_TAG_PATTERN =
            Pattern.compile("\\[plantuml]\\r?\\n(.*?)\\[/plantuml]", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final MediaRenderClient mediaRenderClient;

    public PlantUmlTagRenderService(MediaRenderClient mediaRenderClient) {
        this.mediaRenderClient = mediaRenderClient;
    }

    /**
     * プレビュー向け: PNGをdata URIとして直接埋め込む。CMS認証情報を必要とせず、
     * メディアアップロードも行わない(プレビューは副作用のある外部呼び出しを避けるため)。
     *
     * @throws InvalidPlantUmlTagException レンダリングに失敗した場合
     */
    public String renderForPreview(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }
        Matcher matcher = PLANTUML_TAG_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String wrapped = wrapWithMarkers(matcher.group(1).trim());
            byte[] png = renderPng(wrapped);
            String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
            matcher.appendReplacement(result, Matcher.quoteReplacement("![diagram](" + dataUri + ")"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private byte[] renderPng(String wrappedDiagramSource) {
        try {
            return mediaRenderClient.renderPlantUml(wrappedDiagramSource);
        } catch (AiServiceException e) {
            throw new InvalidPlantUmlTagException("PlantUML図のレンダリングに失敗しました: " + e.getMessage(), e);
        }
    }

    private String wrapWithMarkers(String diagramSource) {
        if (diagramSource.contains("@startuml")) {
            return diagramSource;
        }
        return "@startuml\n" + diagramSource + "\n@enduml";
    }
}
