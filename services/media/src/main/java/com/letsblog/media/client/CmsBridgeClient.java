package com.letsblog.media.client;

import com.letsblog.media.service.CmsBridgeException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * legacy-apiの内部CMSブリッジ(/api/internal/cms/**、issue #573 stage3、
 * {@code com.letsblog.api.controller.CmsMediaBridgeController})を呼び出すクライアント。
 *
 * <p>CMS(WordPress)への実際の接続情報({@code CmsCredentials}、SSH鍵等の秘匿情報を含む)は
 * legacy-api側に留まり、media-serviceへは一切渡らない。media-serviceは「このsite/projectに
 * 対して操作してほしい」という依頼のみを送る。
 *
 * <p>認証はstage1/stage2と同じ暫定策(呼び出し元ユーザーのBearerトークンをそのまま転送)だが、
 * このクライアントは同期呼び出し({@link com.letsblog.media.controller.MediaController}の
 * アップロード、{@code MediaGarbageCollectionService#scan})と非同期呼び出し
 * ({@code MediaGarbageCollectionJobRunner}の削除ループ、バックグラウンドスレッド)の両方から
 * 使われるため、{@code HttpServletRequest}注入ではなく、呼び出し元が明示的に渡した
 * トークン文字列を引数として受け取る統一インターフェースにしている
 * ({@link GenerationJobClient}と同じ理由・パターン)。
 */
@Component
public class CmsBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient restClient;

    public CmsBridgeClient(RestClient.Builder builder, @Value("${app.legacy-api-uri}") String legacyApiUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(legacyApiUri).requestFactory(requestFactory).build();
    }

    public MediaUploadResult uploadMedia(
            String siteKey, String filename, String contentType, byte[] data, String bearerToken) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        ByteArrayResource resource = new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.part("file", resource).header(HttpHeaders.CONTENT_TYPE, contentType != null ? contentType : "application/octet-stream");
        try {
            MediaUploadResult result = restClient.post()
                    .uri("/api/internal/cms/sites/{site}/media", siteKey)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(MediaUploadResult.class);
            if (result == null) {
                throw new CmsBridgeException("legacy-apiから空の応答を受け取りました", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new CmsBridgeException("legacy-apiのメディアアップロード呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public MediaGcScanResult scanMedia(Long projectId, String environment, String bearerToken) {
        try {
            MediaGcScanResult result = restClient.get()
                    .uri("/api/internal/cms/projects/{projectId}/media-scan?environment={environment}",
                            projectId, environment)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(MediaGcScanResult.class);
            if (result == null) {
                throw new CmsBridgeException("legacy-apiから空の応答を受け取りました", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new CmsBridgeException("legacy-apiのメディアスキャン呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public void deleteMedia(Long projectId, String environment, String mediaId, String bearerToken) {
        try {
            restClient.delete()
                    .uri("/api/internal/cms/projects/{projectId}/media/{mediaId}?environment={environment}",
                            projectId, mediaId, environment)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new CmsBridgeException("legacy-apiのメディア削除呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
