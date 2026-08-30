package com.letsblog.publishing.service;

import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.render.MediaRenderClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown本文中の ```plantuml フェンスコードブロックをPlantUMLサーバーでPNGにレンダリングし、
 * CMSのメディアライブラリへアップロードして画像参照に差し替える(投稿パイプライン向け)。
 *
 * <p>プレビュー向け(CMS認証情報を必要としないembedDiagramsForPreview)はcontent-serviceへ移設した
 * (issue #576、{@link com.letsblog.content.service.PlantUmlEmbedService}参照)。こちらはCMS
 * メディアライブラリへのアップロードを伴うためCmsAdapter/CmsCredentialsへの依存が強く、
 * issue #575(publishing-service)の対象になるまで引き続きlegacy-apiに残す。
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
