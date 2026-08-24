package com.letsblog.api.ai;

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
 * media-serviceの{@code /api/generated-images}(GeneratedImageController)を呼び出すクライアント
 * (issue #573 stage4)。
 *
 * <p>{@code generated_images}テーブルの所有権はstage1でmedia-serviceへ移管済みだが、
 * 実際の画像生成AI呼び出し(ComfyUiClient/ChatGptImageClient)・プロバイダー選択
 * (ImageModelService、project_image_settings経由)はいずれもこのissueの移設対象外のまま
 * legacy-apiに残る(PR説明参照)。{@link com.letsblog.api.service.AiAssistService#generateImage}は
 * 引き続き自身で生成AI呼び出しを行い、生成済み画像バイト列とパラメータの保存のみをこのクライアント
 * 経由でmedia-serviceへ委譲する。{@link com.letsblog.api.controller.ProjectController}の
 * アセット画像アップロード(生成済み画像を各環境のWordPressへ追加でアップロードする機能)も、
 * 保存済み画像の読み出しにこのクライアントを使う。
 *
 * <p>認証はstage1のMediaRenderClientと同じ暫定策(呼び出し元ユーザーのBearerトークンをそのまま転送)。
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

    /**
     * 生成済み画像のバイト列とパラメータを保存する。
     *
     * @return 作成されたgenerated_images行のID
     */
    public Long create(
            Long projectId, String prompt, String negativePrompt, Integer steps, Double cfgScale,
            String samplerName, String scheduler, Long seed, Integer width, Integer height, Integer batchSize,
            String checkpoint, String loraName, Double loraWeight, String mimeType, String provider,
            String tagsJson, byte[] imageData) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("prompt", prompt);
        body.put("negativePrompt", negativePrompt);
        body.put("steps", steps);
        body.put("cfgScale", cfgScale);
        body.put("samplerName", samplerName);
        body.put("scheduler", scheduler);
        body.put("seed", seed);
        body.put("width", width);
        body.put("height", height);
        body.put("batchSize", batchSize);
        body.put("checkpoint", checkpoint);
        body.put("loraName", loraName);
        body.put("loraWeight", loraWeight);
        body.put("mimeType", mimeType);
        body.put("provider", provider);
        body.put("tagsJson", tagsJson);
        body.put("imageData", imageData);
        try {
            CreatedGeneratedImage created = restClient.post()
                    .uri("/api/generated-images")
                    .headers(this::setAuthorization)
                    .body(body)
                    .retrieve()
                    .body(CreatedGeneratedImage.class);
            if (created == null) {
                throw new AiServiceException("media-serviceから空の応答を受け取りました", null);
            }
            return created.id();
        } catch (RestClientException e) {
            throw new AiServiceException("media-serviceの生成画像保存呼び出しに失敗しました: " + e.getMessage(), e);
        }
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
                throw new AiServiceException("media-serviceから空の応答を受け取りました", null);
            }
            return data;
        } catch (RestClientException e) {
            throw new AiServiceException("media-serviceの生成画像取得呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }

    private record CreatedGeneratedImage(Long id) {
    }
}
