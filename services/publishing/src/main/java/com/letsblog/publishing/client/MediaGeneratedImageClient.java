package com.letsblog.publishing.client;

import com.letsblog.publishing.render.MediaRenderException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * media-serviceの{@code /api/generated-images}(GeneratedImageController)を呼び出すクライアント。
 * legacy-apiの{@code com.letsblog.api.ai.MediaGeneratedImageClient}から、
 * {@link com.letsblog.publishing.controller.BulkManagementController}のアセット画像アップロード
 * (生成済み画像を各環境のWordPressへ追加でアップロードする機能)が使う{@code fetchImageFile}のみを
 * publishing-serviceへ移設した(issue #708)。画像生成AI呼び出し自体の保存({@code create})は
 * legacy-apiの{@code AiAssistService}専用の依存のため移設しない。
 */
@Component
public class MediaGeneratedImageClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public MediaGeneratedImageClient(
            RestClient.Builder builder,
            @Value("${app.media-service-uri}") String mediaServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(mediaServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    /** 保存済み画像のバイト列を読み出す(常にimage/pngとして保存されている前提、既存の挙動を踏襲)。 */
    public byte[] fetchImageFile(Long generatedImageId) {
        try {
            byte[] data = restClient.get()
                    .uri("/api/generated-images/{id}/file", generatedImageId)
                    .headers(this::setAuthorization)
                    .retrieve()
                    .body(byte[].class);
            if (data == null) {
                throw new MediaRenderException("media-serviceから空の応答を受け取りました", null);
            }
            return data;
        } catch (RestClientException e) {
            throw new MediaRenderException("media-serviceの生成画像取得呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
