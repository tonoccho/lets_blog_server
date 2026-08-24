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
 * media-serviceの{@code /api/comfyui/checkpoints/**}(チェックポイントのダウンロード/削除の
 * 起動、issue #573 stage2)を呼び出すクライアント。
 *
 * <p>{@link com.letsblog.api.service.ComfyUiModelService}は、ジョブ(GenerationJob)の作成・
 * チェックポイント一覧取得(ComfyUiClient経由、legacy-apiに残る)・プロジェクト単位の選択状態
 * (ProjectImageSettingsService経由、legacy-apiに残る)は引き続き自身で行うが、実際のチェックポイント
 * ファイルのダウンロード・削除(共有ボリュームcomfyui_modelsへの書き込み、issue #573で
 * media-serviceへ移設した{@code ComfyUiCheckpointStorageService}/{@code ModelInstallJobRunner})
 * だけをこのクライアント経由で委譲する。呼び出しは起動(トリガー)のみで、実行自体は
 * media-service側で非同期に行われ、進捗・完了はmedia-serviceが
 * {@code PATCH /api/generation-jobs/{id}}(GenerationJobController参照)を呼んで反映する。
 *
 * <p>認証はMediaRenderClientと同じ暫定策(呼び出し元ユーザーのBearerトークンをそのまま転送)。
 */
@Component
public class MediaComfyUiClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // ダウンロード/削除自体は非同期で行われるため、この呼び出し自体はトリガーのみの短時間で返る想定。
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public MediaComfyUiClient(
            RestClient.Builder builder,
            @Value("${app.media-service-uri}") String mediaServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(mediaServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    public void startInstall(Long jobId, String downloadUrl, String fileName) {
        try {
            authorized(restClient.post().uri("/api/comfyui/checkpoints/install"))
                    .body(Map.of("jobId", jobId, "downloadUrl", downloadUrl, "fileName", fileName))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new AiServiceException("media-serviceのチェックポイントダウンロード起動呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public void startDelete(Long jobId, String fileName) {
        try {
            authorized(restClient.post().uri("/api/comfyui/checkpoints/delete"))
                    .body(Map.of("jobId", jobId, "fileName", fileName))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new AiServiceException("media-serviceのチェックポイント削除起動呼び出しに失敗しました: " + e.getMessage(), e);
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
