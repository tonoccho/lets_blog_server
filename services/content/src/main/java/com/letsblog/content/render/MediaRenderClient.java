package com.letsblog.content.render;

import com.letsblog.common.client.ServiceAuthHeaders;
import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.client.SyncServiceException;
import com.letsblog.content.client.AiServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * media-serviceの{@code /api/render/**}(PlantUML/Recharts/Penpot、issue #573)を呼び出すクライアント。
 * legacy-apiのMediaRenderClientと同じ役割だが、content-service側は埋め込みタグの実際の展開
 * (PlantUmlEmbedService/PlantUmlTagRenderService(プレビュー限定)/RechartsTagRenderService/
 * CustomTagGenerationService)を自身で持つため、legacy-apiを経由せず直接media-serviceへ問い合わせる
 * (issue #576)。issue #581(C12)でlbs-commonの{@link SyncServiceClient}へ移行し、C12が採用した
 * 方針(「content → media のレンダリングは30秒/リトライなし/失敗時はプレースホルダ表示」の例)を
 * そのまま適用した。方針の詳細はdocs/SYNC_SERVICE_CALLS.md参照。
 *
 * <p>認証は、log-writer(#572)のIdentityClient/GenerationJobClientと同じ方式として、呼び出し元
 * (このクラスを使う各サービスを呼んだユーザー)のBearerトークンをそのまま転送する。
 *
 * <p>フォールバック方針はこのクライアント自体では決めない。呼び出し元(PlantUmlEmbedService等)が、
 * 呼び出し内容の性質(プレビュー限定/副作用の有無)に応じてプレースホルダ表示または明確なエラーを
 * 選ぶ(docs/SYNC_SERVICE_CALLS.md参照)。
 */
@Component
public class MediaRenderClient {

    private final SyncServiceClient client;
    private final HttpServletRequest request;

    public MediaRenderClient(
            RestClient.Builder builder,
            @Value("${app.media-service-uri}") String mediaServiceUri,
            HttpServletRequest request) {
        this.client = SyncServiceClient.builder(builder, "media-service", mediaServiceUri)
                .profile(SyncCallProfile.RENDER)
                .build();
        this.request = request;
    }

    public byte[] renderPlantUml(String source) {
        try {
            return client.post(
                    "/api/render/plantuml", new Object[0], Map.of("source", source), byte[].class,
                    ServiceAuthHeaders.forwardedBearer(request));
        } catch (SyncServiceException e) {
            throw new AiServiceException("media-serviceのPlantUMLレンダリング呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** @param type "bar"/"line"/"area"/"pie"(小文字) */
    public String renderRecharts(
            String type, List<Map<String, Object>> data, String xAxisKey, List<String> seriesKeys,
            List<String> colors, boolean stacked, int width, int height, String textColor, String gridColor,
            String yAxisLabel) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type", type);
        body.put("data", data);
        body.put("xAxisKey", xAxisKey);
        body.put("seriesKeys", seriesKeys);
        body.put("colors", colors);
        body.put("stacked", stacked);
        body.put("width", width);
        body.put("height", height);
        body.put("textColor", textColor);
        body.put("gridColor", gridColor);
        body.put("yAxisLabel", yAxisLabel);
        try {
            RechartsRenderResult result = client.post(
                    "/api/render/recharts", new Object[0], body, RechartsRenderResult.class,
                    ServiceAuthHeaders.forwardedBearer(request));
            if (result == null) {
                throw new RechartsRenderException("media-serviceから空の応答を受け取りました");
            }
            return result.html();
        } catch (SyncServiceException e) {
            throw new RechartsRenderException("media-serviceのRechartsレンダリング呼び出しに失敗しました: " + e.getMessage());
        }
    }

    public DesignFile createPenpotDesignFile(String fileName, String promptContext) {
        try {
            PenpotDesignFileResult result = client.post(
                    "/api/render/penpot/design-file", new Object[0],
                    Map.of("fileName", fileName, "promptContext", promptContext == null ? "" : promptContext),
                    PenpotDesignFileResult.class, ServiceAuthHeaders.forwardedBearer(request));
            if (result == null) {
                throw new AiServiceException("media-serviceから空の応答を受け取りました", null);
            }
            return new DesignFile(result.fileId(), result.projectId(), result.url());
        } catch (SyncServiceException e) {
            throw new AiServiceException("media-serviceのPenpotデザインファイル作成呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** media-service側の{@code PenpotClient.DesignFile}相当。CustomTagGenerationServiceが参照する。 */
    public record DesignFile(String fileId, String projectId, String url) {
    }

    private record RechartsRenderResult(String html) {
    }

    private record PenpotDesignFileResult(String fileId, String projectId, String url) {
    }
}
