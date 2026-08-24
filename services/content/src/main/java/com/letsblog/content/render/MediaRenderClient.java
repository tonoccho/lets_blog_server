package com.letsblog.content.render;

import com.letsblog.content.client.AiServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * media-serviceの{@code /api/render/**}(PlantUML/Recharts/Penpot、issue #573)を呼び出すクライアント。
 * legacy-apiのMediaRenderClientと同じ役割だが、content-service側は埋め込みタグの実際の展開
 * (PlantUmlEmbedService/PlantUmlTagRenderService(プレビュー限定)/RechartsTagRenderService/
 * CustomTagGenerationService)を自身で持つため、legacy-apiを経由せず直接media-serviceへ問い合わせる
 * (issue #576)。
 *
 * <p>認証は、log-writer(#572)のIdentityClient/GenerationJobClientと同じ暫定策として、呼び出し元
 * (このクラスを使う各サービスを呼んだユーザー)のBearerトークンをそのまま転送する。
 *
 * <p>受入基準「media-serviceへの呼び出しがタイムアウト・リトライ設定を持つ」(issue #576)に対応する
 * ため、legacy-api版には無かった簡易リトライ(一時的なネットワークエラーのみを対象に、短い固定
 * バックオフで最大{@value #MAX_ATTEMPTS}回試行)を追加している。C12(#581、サービス間同期呼び出しの
 * 規約策定)が未着手の間の暫定策であり、サーキットブレーカー等は持たない。
 */
@Component
public class MediaRenderClient {

    private static final Logger log = LoggerFactory.getLogger(MediaRenderClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // PlantUML/Rechartsのレンダリングは外部プロセス(PlantUMLサーバー/ヘッドレスChromium)に
    // 依存し数秒かかりうるため、単純なJSON APIより長めのタイムアウトを取る
    // (RechartsRenderer自体のPlaywrightタイムアウトが10秒のため、それより余裕を持たせる)。
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(500);

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
            return withRetry("renderPlantUml", () -> authorized(restClient.post().uri("/api/render/plantuml"))
                    .body(Map.of("source", source))
                    .retrieve()
                    .body(byte[].class));
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
            RechartsRenderResult result = withRetry("renderRecharts", () ->
                    authorized(restClient.post().uri("/api/render/recharts"))
                            .body(body)
                            .retrieve()
                            .body(RechartsRenderResult.class));
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
            PenpotDesignFileResult result = withRetry("createPenpotDesignFile", () ->
                    authorized(restClient.post().uri("/api/render/penpot/design-file"))
                            .body(Map.of("fileName", fileName, "promptContext", promptContext == null ? "" : promptContext))
                            .retrieve()
                            .body(PenpotDesignFileResult.class));
            if (result == null) {
                throw new AiServiceException("media-serviceから空の応答を受け取りました", null);
            }
            return new DesignFile(result.fileId(), result.projectId(), result.url());
        } catch (RestClientException e) {
            throw new AiServiceException("media-serviceのPenpotデザインファイル作成呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 一時的なネットワークエラー({@link RestClientException}のうちレスポンス自体を受け取れなかった
     * もの)のみを対象に、固定バックオフで最大{@link #MAX_ATTEMPTS}回試行する。media-service側が
     * 4xx/5xxを明示的に返した場合(RestClientResponseException)はリトライしても結果が変わらない
     * ことが多いため対象外とし、直ちに例外を伝播させる。
     */
    private <T> T withRetry(String operation, java.util.function.Supplier<T> action) {
        RestClientException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (org.springframework.web.client.RestClientResponseException e) {
                // media-service側が応答済み(4xx/5xx)。再試行しても結果は変わらない可能性が高いため即座に伝播。
                throw e;
            } catch (RestClientException e) {
                lastError = e;
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("media-service呼び出し({})が失敗したため再試行します({}/{}): {}",
                            operation, attempt, MAX_ATTEMPTS, e.getMessage());
                    sleep(RETRY_BACKOFF.multipliedBy(attempt));
                }
            }
        }
        throw lastError;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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
