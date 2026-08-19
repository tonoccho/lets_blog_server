package com.letsblog.api.service;

import com.letsblog.api.ai.AiServiceException;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.render.PlantUmlClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
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
            String wrapped = wrapWithMarkers(matcher.group(1).trim());
            byte[] png = renderPng(wrapped);
            String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
            matcher.appendReplacement(result, Matcher.quoteReplacement("![diagram](" + dataUri + ")"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 投稿向け: PNGを生成しCMSメディアライブラリへアップロードした上で画像参照に差し替える。
     * priorUploadsに同一内容(sha256)のダイアグラムが既にあり、CMS側のメディアも実在する場合は
     * 再生成・再アップロードせず既存のURLを再利用する(issue #499。通常画像の再利用判定と同じ方針)。
     *
     * @throws InvalidPlantUmlTagException レンダリング/アップロードに失敗した場合
     */
    public DiagramEmbedResult render(
            CmsCredentials credentials, String markdown, Map<String, UploadedImageInfo> priorUploads) {
        if (markdown == null || markdown.isEmpty()) {
            return new DiagramEmbedResult(markdown, priorUploads);
        }
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
        Matcher matcher = PLANTUML_TAG_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();
        Map<String, UploadedImageInfo> updatedUploads = new LinkedHashMap<>(priorUploads);
        int index = 0;
        while (matcher.find()) {
            String wrapped = wrapWithMarkers(matcher.group(1).trim());
            String sha256 = sha256Hex(wrapped);
            String cacheKey = "plantuml:" + sha256;
            UploadedImageInfo prior = updatedUploads.get(cacheKey);
            boolean reusePrior = prior != null && cmsAdapter.mediaExists(credentials, prior.mediaId());

            UploadedImageInfo current;
            if (reusePrior) {
                current = prior;
            } else {
                byte[] png = renderPng(wrapped);
                String fileName = "plantuml-tag-" + (++index) + ".png";
                MediaUploadResult uploaded = cmsAdapter.uploadMedia(credentials, fileName, "image/png", png);
                current = new UploadedImageInfo(sha256, uploaded.url(), uploaded.id());
                updatedUploads.put(cacheKey, current);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement("![diagram](" + current.url() + ")"));
        }
        matcher.appendTail(result);
        return new DiagramEmbedResult(result.toString(), updatedUploads);
    }

    private byte[] renderPng(String wrappedDiagramSource) {
        try {
            return plantUmlClient.renderPng(wrappedDiagramSource);
        } catch (AiServiceException e) {
            throw new InvalidPlantUmlTagException("PlantUML図のレンダリングに失敗しました: " + e.getMessage(), e);
        }
    }

    private String sha256Hex(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256アルゴリズムが利用できません", e);
        }
    }

    private String wrapWithMarkers(String diagramSource) {
        if (diagramSource.contains("@startuml")) {
            return diagramSource;
        }
        return "@startuml\n" + diagramSource + "\n@enduml";
    }
}
