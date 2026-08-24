package com.letsblog.content.client;

import com.letsblog.content.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * identity-serviceの{@code GET /api/identity/me}を、呼び出し元のBearerトークンをそのまま
 * 転送して問い合わせるクライアント(log-writer/media-service/ai-serviceと同じ暫定策)。
 *
 * <p>content-service(lbs_contentスキーマ)はADR-0004によりusersテーブルへクロススキーマ
 * アクセスできないため、「自分自身のuserId」「admin権限を持つか」の解決を、常にidentity-service
 * への同期HTTP呼び出しに委ねる。C12(#581、サービス間同期呼び出しの規約策定)が未着手の間の
 * 暫定策であり、リトライ・サーキットブレーカーは持たず、リクエストごとに短いタイムアウトで1回呼ぶのみ。
 */
@Component
public class IdentityClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;

    public IdentityClient(RestClient.Builder builder, @Value("${app.identity-service-uri}") String identityServiceUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = builder
                .baseUrl(identityServiceUri)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * @param bearerToken {@code Authorization}ヘッダーの値をそのまま渡す(例: {@code "Bearer xxx"}）。
     */
    public ActorProfile fetchProfile(String bearerToken) {
        try {
            ActorProfile profile = restClient.get()
                    .uri("/api/identity/me")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(ActorProfile.class);
            if (profile == null) {
                throw new IdentityServiceUnavailableException("identity-serviceから空の応答を受け取りました", null);
            }
            return profile;
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException("identity-serviceの/api/identity/me呼び出しに失敗しました", e);
        }
    }
}
