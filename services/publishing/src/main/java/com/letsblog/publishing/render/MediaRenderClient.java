package com.letsblog.publishing.render;

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

/**
 * media-serviceの{@code /api/render/plantuml}を呼び出すクライアント。legacy-apiの
 * {@code com.letsblog.api.render.MediaRenderClient}(issue #573)から、publishing-serviceの
 * 公開パイプライン(PlantUmlEmbedService/PlantUmlTagRenderService)が実際に使うPlantUMLレンダリング
 * 呼び出しのみを移設した(issue #707)。Recharts/Penpotのレンダリング呼び出しはこのパイプラインでは
 * 使わないため移設していない(legacy-api側に残る、CustomTagGenerationService/RechartsTagRenderService
 * 向け)。
 */
@Component
public class MediaRenderClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // PlantUMLのレンダリングは外部プロセス(PlantUMLサーバー)に依存し数秒かかりうるため、
    // 単純なJSON APIより長めのタイムアウトを取る(legacy-api版と同じ値)。
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
