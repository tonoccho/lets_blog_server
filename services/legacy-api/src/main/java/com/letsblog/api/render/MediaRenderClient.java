package com.letsblog.api.render;

import com.letsblog.api.ai.AiServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * media-serviceの{@code /api/render/**}(PlantUML/Recharts/Penpot、issue #573)を呼び出すクライアント。
 *
 * <p>{@link com.letsblog.api.service.PlantUmlEmbedService}/{@link com.letsblog.api.service.PlantUmlTagRenderService}/
 * {@link com.letsblog.api.service.RechartsTagRenderService}/{@link com.letsblog.api.service.CustomTagGenerationService}は、
 * CMSアップロード・タグ置換等のオーケストレーション自体は引き続きlegacy-api側(投稿パイプライン、
 * content-serviceの将来の抽出範囲)で行うが、実際の(遅い・外部プロセス依存の)レンダリング処理だけを
 * ここ経由でmedia-serviceへ委譲する。#573のPR説明にある設計判断(投稿パイプラインへの影響を
 * 最小化するため、PlantUmlEmbedService等自体は移設せずレンダリング呼び出しだけを差し替える)を参照。
 *
 * <p>認証は、log-writerのIdentityClient/GenerationJobClient(#572)と同じ暫定策として、呼び出し元
 * (このクラスを使う各サービスを呼んだユーザー)のBearerトークンをそのまま転送する。これらの呼び出しは
 * 全て同期的なユーザーリクエストの処理中(投稿・プレビュー・カスタムタグ生成)に発生するため、
 * バックグラウンドスレッドでのトークン失効の懸念はない。C12(#581)でサービス間同期呼び出しの
 * 標準クライアントが定まったらそちらへ寄せる想定。
 */
@Component
public class MediaRenderClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // PlantUML/Rechartsのレンダリングは外部プロセス(PlantUMLサーバー/ヘッドレスChromium)に
    // 依存し数秒かかりうるため、単純なJSON APIより長めのタイムアウトを取る
    // (RechartsRenderer自体のPlaywrightタイムアウトが10秒のため、それより余裕を持たせる)。
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public MediaRenderClient(
            RestClient.Builder builder,
            @Value("${app.media-service-uri}") String mediaServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(mediaServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    public byte[] renderPlantUml(String source) {
        try {
            return authorized(restClient.post().uri("/api/render/plantuml"))
                    .body(Map.of("source", source))
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException e) {
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
            RechartsRenderResult result = authorized(restClient.post().uri("/api/render/recharts"))
                    .body(body)
                    .retrieve()
                    .body(RechartsRenderResult.class);
            if (result == null) {
                throw new RechartsRenderException("media-serviceから空の応答を受け取りました");
            }
            return result.html();
        } catch (RestClientException e) {
            throw new RechartsRenderException("media-serviceのRechartsレンダリング呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public DesignFile createPenpotDesignFile(String fileName, String promptContext) {
        try {
            PenpotDesignFileResult result = authorized(restClient.post().uri("/api/render/penpot/design-file"))
                    .body(Map.of("fileName", fileName, "promptContext", promptContext == null ? "" : promptContext))
                    .retrieve()
                    .body(PenpotDesignFileResult.class);
            if (result == null) {
                throw new AiServiceException("media-serviceから空の応答を受け取りました", null);
            }
            return new DesignFile(result.fileId(), result.projectId(), result.url());
        } catch (RestClientException e) {
            throw new AiServiceException("media-serviceのPenpotデザインファイル作成呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private RestClient.RequestBodySpec authorized(RestClient.RequestBodySpec spec) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            return spec.header(HttpHeaders.AUTHORIZATION, bearerToken);
        }
        return spec;
    }

    /** media-service側の{@code PenpotClient.DesignFile}相当。CustomTagGenerationServiceが参照する。 */
    public record DesignFile(String fileId, String projectId, String url) {
    }

    private record RechartsRenderResult(String html) {
    }

    private record PenpotDesignFileResult(String fileId, String projectId, String url) {
    }
}
