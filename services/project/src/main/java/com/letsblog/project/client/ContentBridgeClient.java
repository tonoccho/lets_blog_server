package com.letsblog.project.client;

import com.letsblog.project.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * content-serviceの内部ブリッジ({@code /api/internal/content/**})を呼び出すクライアント(issue #1558)。
 * WordPress の letsblog プラグインへ送る内容(タグ定義・統合CSS・プレフィックス・デザイン)とそのハッシュを、
 * 同期のたびに最新の状態で読む。認証は呼び出し元のBearerトークンを転送する。
 */
@Component
public class ContentBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // 内容の組み立てがproject-service(デザイン設定・slug)への呼び出しを伴うため、通常のブリッジより長めにする。
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient restClient;

    public ContentBridgeClient(
            RestClient.Builder builder, @Value("${app.content-service-uri}") String contentServiceUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(contentServiceUri).requestFactory(requestFactory).build();
    }

    /**
     * @param payload 送る内容(JSON文字列。プラグインへはこの文字列をそのまま渡す)
     * @param hash    {@code payload}のSHA-256(16進小文字)
     */
    public record SyncPayload(String payload, String hash) {
    }

    public SyncPayload fetchSyncPayload(Long projectId, String bearerToken) {
        try {
            SyncPayload result = restClient.get()
                    .uri("/api/internal/content/projects/{projectId}/letsblog-sync-payload", projectId)
                    .headers(headers -> {
                        if (bearerToken != null && !bearerToken.isBlank()) {
                            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
                        }
                    })
                    .retrieve()
                    .body(SyncPayload.class);
            if (result == null) {
                throw new IdentityServiceUnavailableException("content-serviceから空の応答を受け取りました", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "content-serviceの同期内容の取得に失敗しました: " + e.getMessage(), e);
        }
    }
}
