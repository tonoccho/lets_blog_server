package com.letsblog.api.service;

import com.letsblog.api.ai.AiServiceException;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.render.PlantUmlClient;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown本文中の組み込みタグ`[plantuml]`〜`[/plantuml]`(本文はPlantUML記法)を画像に展開する(issue #344)。
 * 既存の ```plantuml フェンスコードブロック記法({@link PlantUmlEmbedService}が処理)とは併存し、
 * 置き換えない。
 *
 * [recharts]タグ(issue #340)と同じ方針で、記法エラー・レンダリング失敗は
 * {@link InvalidPlantUmlTagException}として投げる:
 * - プレビュー({@link ArticlePreviewService#renderHtml})はこれを捕捉し、レンダリングを中止して
 *   エラーメッセージのみを表示する。
 * - 投稿({@link PostPublishService#publish})はこれを未捕捉のまま伝播させ、投稿自体を拒否する
 *   ({@link com.letsblog.api.config.GlobalExceptionHandler}が400として返す)。
 */
@Service
public class PlantUmlTagRenderService {

    private static final Pattern PLANTUML_TAG_PATTERN =
            Pattern.compile("\\[plantuml]\\r?\\n(.*?)\\[/plantuml]", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final PlantUmlClient plantUmlClient;
    private final CmsAdapterFactory cmsAdapterFactory;

    public PlantUmlTagRenderService(PlantUmlClient plantUmlClient, CmsAdapterFactory cmsAdapterFactory) {
        this.plantUmlClient = plantUmlClient;
        this.cmsAdapterFactory = cmsAdapterFactory;
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
            byte[] png = renderPng(matcher.group(1));
            String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
            matcher.appendReplacement(result, Matcher.quoteReplacement("![diagram](" + dataUri + ")"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 投稿向け: PNGを生成しCMSメディアライブラリへアップロードした上で画像参照に差し替える。
     *
     * @throws InvalidPlantUmlTagException レンダリング/アップロードに失敗した場合
     */
    public String render(CmsCredentials credentials, String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
        Matcher matcher = PLANTUML_TAG_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (matcher.find()) {
            byte[] png = renderPng(matcher.group(1));
            String fileName = "plantuml-tag-" + (++index) + ".png";
            MediaUploadResult uploaded = cmsAdapter.uploadMedia(credentials, fileName, "image/png", png);
            matcher.appendReplacement(result, Matcher.quoteReplacement("![diagram](" + uploaded.url() + ")"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private byte[] renderPng(String diagramSource) {
        try {
            return plantUmlClient.renderPng(wrapWithMarkers(diagramSource.trim()));
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
