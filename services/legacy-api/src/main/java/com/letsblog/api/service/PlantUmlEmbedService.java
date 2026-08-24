package com.letsblog.api.service;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.render.MediaRenderClient;
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
 * Markdown本文中の ```plantuml フェンスコードブロックをPlantUMLサーバーでPNGにレンダリングし、
 * CMSのメディアライブラリへアップロードして画像参照に差し替える。
 */
@Service
public class PlantUmlEmbedService {

    private static final Pattern PLANTUML_BLOCK_PATTERN =
            Pattern.compile("```plantuml\\s*\\n(.*?)```", Pattern.DOTALL);

    private final MediaRenderClient mediaRenderClient;
    private final CmsAdapterFactory cmsAdapterFactory;

    public PlantUmlEmbedService(MediaRenderClient mediaRenderClient, CmsAdapterFactory cmsAdapterFactory) {
        this.mediaRenderClient = mediaRenderClient;
        this.cmsAdapterFactory = cmsAdapterFactory;
    }

    /**
     * priorUploadsに同一内容(sha256)のダイアグラムが既にあり、CMS側のメディアも実在する場合は
     * 再生成・再アップロードせず既存のURLを再利用する(issue #499。通常画像の再利用判定と同じ方針)。
     */
    public DiagramEmbedResult embedDiagrams(
            CmsCredentials credentials, String markdown, Map<String, UploadedImageInfo> priorUploads) {
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
        Matcher matcher = PLANTUML_BLOCK_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();
        Map<String, UploadedImageInfo> updatedUploads = new LinkedHashMap<>(priorUploads);
        int index = 0;

        while (matcher.find()) {
            String diagramSource = matcher.group(1).trim();
            String wrapped = wrapWithMarkers(diagramSource);
            String sha256 = sha256Hex(wrapped);
            String cacheKey = "plantuml:" + sha256;
            UploadedImageInfo prior = updatedUploads.get(cacheKey);
            boolean reusePrior = prior != null && cmsAdapter.mediaExists(credentials, prior.mediaId());

            UploadedImageInfo current;
            if (reusePrior) {
                current = prior;
            } else {
                byte[] png = mediaRenderClient.renderPlantUml(wrapped);
                String fileName = "plantuml-" + (++index) + ".png";
                MediaUploadResult uploaded = cmsAdapter.uploadMedia(credentials, fileName, "image/png", png);
                current = new UploadedImageInfo(sha256, uploaded.url(), uploaded.id());
                updatedUploads.put(cacheKey, current);
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement("![diagram](" + current.url() + ")"));
        }
        matcher.appendTail(result);

        return new DiagramEmbedResult(result.toString(), updatedUploads);
    }

    /**
     * プレビュー向けに```plantumlフェンスコードブロックをPNGへレンダリングし、data URIとして
     * 画像参照に差し替える。embedDiagramsと異なりCMS認証情報を必要とせず、メディアアップロードも
     * 行わない(プレビューは副作用のある外部呼び出しを避けるため)。
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

    private String sha256Hex(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256アルゴリズムが利用できません", e);
        }
    }
}
