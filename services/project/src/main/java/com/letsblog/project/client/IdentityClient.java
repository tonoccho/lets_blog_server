package com.letsblog.project.client;

import com.letsblog.project.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.util.Optional;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * identity-serviceの{@code GET /api/identity/me}を、呼び出し元のBearerトークンをそのまま
 * 転送して問い合わせるクライアント(log-writer/media-service/ai-service/content-serviceと同じ暫定策)。
 *
 * <p>project-service(lbs_projectスキーマ)はADR-0004によりusersテーブルへクロススキーマ
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

    /**
     * 操作者を解決する。<b>認証・認可の結果</b>と<b>サービス障害</b>を区別する(issue #829)。
     *
     * <p>identity-serviceが401/403を返すのは「このトークンでは操作者を解決できない」という
     * 正常な判定結果であって障害ではない(#816以降、無効化されたユーザーがこれに当たる)。
     * {@link #fetchProfile}は{@code RestClientException}を一律に
     * {@code IdentityServiceUnavailableException}へ翻訳するため、そのままでは502になり、
     * 無効化ユーザー起因の502と本当のidentity-service障害の502が区別できない。
     *
     * <p>401/403は{@link Optional#empty()}(操作者なし)として返す。呼び出し元の
     * {@code requireAdmin()}等がその先で403を返すため、拒否されること自体は変わらない。
     * <b>5xx・タイムアウト・通信断は従来どおり例外のまま</b>伝播させる
     * (identity-service障害時に「操作者なし」へ縮退すると権限チェックが素通りしうるため)。
     */
    public Optional<ActorProfile> lookupProfile(String bearerToken) {
        try {
            return Optional.of(fetchProfile(bearerToken));
        } catch (IdentityServiceUnavailableException e) {
            if (e.getCause() instanceof RestClientResponseException response
                    && isAuthRejection(response.getStatusCode().value())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** identity-serviceからの401/403は「操作者を解決できない」という判定結果で、障害ではない。 */
    private static boolean isAuthRejection(int statusCode) {
        return statusCode == 401 || statusCode == 403;
    }
}
