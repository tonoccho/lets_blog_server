package com.letsblog.common.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;

/**
 * OAuth2 Client Credentials Grantでサービス用アクセストークンを取得・キャッシュ・期限前更新する
 * 共通部品(#567)。サービス間の実際のHTTP呼び出し(例: 将来Phase 19で分割される
 * publishing→content等のサービス間通信)が {@code letsblog-services} クライアントでの認証を
 * 必要とする際に、各サービスから共通利用することを想定する。
 *
 * <p>identity-serviceがKeycloak Admin API呼び出し用に個別実装していたトークン取得・キャッシュ・
 * 期限前更新ロジック(#562の{@code KeycloakAdminClient#fetchAccessToken})を一般化したもの。
 * 挙動(キャッシュ、期限前の安全マージン、エラー時の例外化)は元の実装を踏襲している。
 *
 * <p>Boot 4ではRestClientの既定JSONコンバータがJackson3(tools.jackson)になったが、本クラスは
 * 既存の外部API連携クライアント群(KeycloakAdminClient/GithubClient等)と同じ流儀で
 * com.fasterxml.jackson.databind.JsonNode(Jackson2)を使ってレスポンスを読む。Jackson3の
 * コンバータはJsonNode.class(Jackson2)を誤って受理した上でデシリアライズに失敗するため、
 * Jackson3コンバータを外しJackson2コンバータへ差し替える
 * (services/legacy-api/.../config/LegacyJacksonRestClientConfig参照)。
 *
 * <p>トークンエンドポイントへの到達不可・エラー応答が連続した場合は{@link CircuitBreaker}を
 * 開き、以降のクールダウン期間中は毎回タイムアウトを待たず即座に失敗させる(#567の受入基準)。
 * RestClientの接続/読み取りタイムアウト自体の設定は呼び出し側(restClientBuilderに設定済みの
 * requestFactory)の責務とする方針も、既存の外部API連携クライアントと同じ。
 */
public class ServiceTokenClient {

    private final RestClient tokenClient;
    private final String clientId;
    private final String clientSecret;
    private final CircuitBreaker circuitBreaker;

    private volatile CachedToken cachedToken;

    public ServiceTokenClient(
            RestClient.Builder restClientBuilder, String tokenUri, String clientId, String clientSecret) {
        this(restClientBuilder, tokenUri, clientId, clientSecret, CircuitBreaker.withDefaults());
    }

    ServiceTokenClient(
            RestClient.Builder restClientBuilder,
            String tokenUri,
            String clientId,
            String clientSecret,
            CircuitBreaker circuitBreaker) {
        RestClient.Builder builder = restClientBuilder.clone().baseUrl(tokenUri);
        preferJackson2(builder);
        this.tokenClient = builder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.circuitBreaker = circuitBreaker;
    }

    private static void preferJackson2(RestClient.Builder builder) {
        builder.messageConverters(converters -> {
            converters.removeIf(AbstractJacksonHttpMessageConverter.class::isInstance);
            converters.add(0, new MappingJackson2HttpMessageConverter());
        });
    }

    /**
     * サービス用アクセストークンを返す。有効期限内であればキャッシュを再利用し、
     * トークンエンドポイントには問い合わせない。
     *
     * @throws ServiceTokenUnavailableException トークン取得に失敗した場合、または
     *         サーキットブレーカー作動中で取得を試みなかった場合
     */
    public synchronized String getAccessToken() {
        CachedToken current = cachedToken;
        if (current != null && !current.isExpiring()) {
            return current.accessToken();
        }

        circuitBreaker.checkAllowed();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        try {
            JsonNode response = tokenClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.has("access_token")) {
                circuitBreaker.recordFailure();
                throw new ServiceTokenUnavailableException(
                        "サービストークン取得レスポンスが不正です(client_id=" + clientId + ")");
            }
            String accessToken = response.get("access_token").asText();
            long expiresInSeconds = response.path("expires_in").asLong(60);
            // 早めに更新して境界での失効を避ける(最低5秒は先読みしない)。
            long safetyMarginSeconds = Math.min(10, Math.max(expiresInSeconds - 5, 0));
            Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds - safetyMarginSeconds);
            cachedToken = new CachedToken(accessToken, expiresAt);
            circuitBreaker.recordSuccess();
            return accessToken;
        } catch (RestClientResponseException e) {
            circuitBreaker.recordFailure();
            throw new ServiceTokenUnavailableException(
                    "サービストークンの取得に失敗しました(client_id=" + clientId + "): "
                            + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (ServiceTokenUnavailableException e) {
            throw e;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            throw new ServiceTokenUnavailableException(
                    "サービストークンエンドポイントへの到達に失敗しました(client_id=" + clientId + "): "
                            + e.getMessage(), e);
        }
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
        boolean isExpiring() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
