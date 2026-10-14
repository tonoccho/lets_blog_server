package com.letsblog.publishing.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * media-serviceの{@code /api/render/plantuml}を呼び出すクライアント。legacy-apiの
 * {@code com.letsblog.api.render.MediaRenderClient}(issue #573)から、publishing-serviceの
 * 公開パイプライン(PlantUmlEmbedService/PlantUmlTagRenderService)が実際に使うPlantUMLレンダリング
 * 呼び出しのみを移設した(issue #707)。Rechartsのレンダリング呼び出しはこのパイプラインでは
 * 使わないため移設していない(legacy-api側に残る、CustomTagGenerationService/RechartsTagRenderService
 * 向け)。
 */
@Component
public class MediaRenderClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // PlantUMLのレンダリングは外部プロセス(PlantUMLサーバー)に依存し数秒かかりうるため、
    // 単純なJSON APIより長めのタイムアウトを取る(legacy-api版と同じ値)。
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private static final String REJECTED_PREFIX = "PlantUML が図を描けませんでした";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** media-serviceの400本文({"error": ...})から拒否の文言を作る。読めない・別の文言なら定型文にする。 */
    static String rejectionMessage(String responseBody) {
        try {
            JsonNode error = JSON.readTree(responseBody).get("error");
            if (error != null && error.isTextual() && error.asText().startsWith(REJECTED_PREFIX)) {
                return error.asText();
            }
        } catch (Exception e) {
            // 本文が読めない場合は定型文にする。
        }
        return REJECTED_PREFIX + ": media-service が 400 を返しました";
    }

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
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400) {
                throw new PlantUmlRenderRejectedException(rejectionMessage(e.getResponseBodyAsString()));
            }
            throw new MediaRenderException("media-serviceのPlantUMLレンダリング呼び出しに失敗しました: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new MediaRenderException("media-serviceのPlantUMLレンダリング呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private RestClient.RequestBodySpec authorized(RestClient.RequestBodySpec spec) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            return spec.header(HttpHeaders.AUTHORIZATION, bearerToken);
        }
        return spec;
    }
}
