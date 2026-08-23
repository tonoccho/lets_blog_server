package com.letsblog.identity.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;

/**
 * identity-serviceからKeycloak Admin REST APIを呼び出す薄いクライアント(#562)。
 * GithubClient/AdSenseClient等と同様、専用SDKは導入せずRestClientの薄いラッパーとして実装する。
 *
 * <p>クライアントクレデンシャルズグラントでアクセストークンを取得し、有効期限内はキャッシュして再利用する
 * (Keycloakへのリクエストのたびにトークンエンドポイントを叩かない)。
 *
 * <p>すべてのメソッドは、Keycloakへの到達不可・エラーレスポンスを問わず{@link KeycloakUserSyncException}
 * に変換して送出する(#562の受入基準: Keycloak停止時に明確なエラーを返し、暗黙に成功しない)。
 */
public class KeycloakAdminClient {

    private final RestClient tokenClient;
    private final RestClient adminClient;
    private final KeycloakAdminProperties properties;

    private volatile CachedToken cachedToken;

    /**
     * restClientBuilderには、接続断/無応答時に長時間ハングしないよう
     * タイムアウト付きのrequestFactoryを設定済みであることを期待する
     * (本番ではKeycloakAdminClientConfig#keycloakAdminRestClientBuilderが設定する。
     * GithubClientConfigと同様、requestFactoryの選定はクライアント自身ではなく呼び出し側の
     * 責務とすることで、テストでMockRestServiceServerを差し込めるようにしている)。
     */
    public KeycloakAdminClient(RestClient.Builder restClientBuilder, KeycloakAdminProperties properties) {
        this.properties = properties;

        RestClient.Builder tokenBuilder = restClientBuilder.clone().baseUrl(properties.getTokenUri());
        preferJackson2(tokenBuilder);
        this.tokenClient = tokenBuilder.build();

        RestClient.Builder adminBuilder = restClientBuilder.clone().baseUrl(properties.getAdminBaseUri());
        preferJackson2(adminBuilder);
        this.adminClient = adminBuilder.build();
    }

    /**
     * Boot 4ではRestClientの既定JSONコンバータがJackson3(tools.jackson)になったが、本クラスは
     * com.fasterxml.jackson.databind.JsonNode/ObjectNode(Jackson2)でリクエスト/レスポンスを組み立てている
     * (GithubClient等、既存の外部API連携クライアントと同じ流儀。
     * services/legacy-api/.../config/LegacyJacksonRestClientConfig参照)。
     * Jackson3のコンバータはJsonNode.class(Jackson2)を誤って受理した上でデシリアライズに失敗するため、
     * Jackson3コンバータを外しJackson2コンバータへ差し替える。
     */
    private static void preferJackson2(RestClient.Builder builder) {
        builder.messageConverters(converters -> {
            converters.removeIf(AbstractJacksonHttpMessageConverter.class::isInstance);
            converters.add(0, new MappingJackson2HttpMessageConverter());
        });
    }

    /**
     * Keycloakにユーザーを新規作成し、生成されたsub(ユーザーID)を返す。
     *
     * @param requirePasswordReset trueの場合、初回ログイン前にパスワード再設定を要求する
     *                              (requiredActions=UPDATE_PASSWORD。#562の移行スクリプトが利用する)
     */
    public String createUser(String email, String firstName, String lastName, boolean requirePasswordReset) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("username", email);
        body.put("email", email);
        body.put("enabled", true);
        body.put("emailVerified", true);
        if (firstName != null && !firstName.isBlank()) {
            body.put("firstName", firstName);
        }
        if (lastName != null && !lastName.isBlank()) {
            body.put("lastName", lastName);
        }
        if (requirePasswordReset) {
            ArrayNode requiredActions = JsonNodeFactory.instance.arrayNode();
            requiredActions.add("UPDATE_PASSWORD");
            body.set("requiredActions", requiredActions);
        }

        try {
            HttpHeaders headers = adminClient.post()
                    .uri("/users")
                    .header("Authorization", "Bearer " + fetchAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders();
            String location = headers.getFirst(HttpHeaders.LOCATION);
            if (location == null || location.isBlank()) {
                throw new KeycloakUserSyncException(
                        "Keycloakユーザー作成のレスポンスからIDを取得できませんでした(email=" + email + ")");
            }
            return location.substring(location.lastIndexOf('/') + 1);
        } catch (RestClientResponseException e) {
            throw new KeycloakUserSyncException(errorMessage("ユーザー作成(email=" + email + ")", e), e);
        } catch (KeycloakUserSyncException e) {
            throw e;
        } catch (Exception e) {
            throw connectionFailure("ユーザー作成(email=" + email + ")", e);
        }
    }

    /** プロフィール(firstName/lastName)をKeycloak側にも反映する。 */
    public void updateProfile(String keycloakSub, String firstName, String lastName) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("firstName", firstName != null ? firstName : "");
        body.put("lastName", lastName != null ? lastName : "");
        putUser(keycloakSub, body, "プロフィール更新");
    }

    /** Keycloak側のユーザーの有効/無効を切り替える(#562の「無効化」)。 */
    public void setEnabled(String keycloakSub, boolean enabled) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("enabled", enabled);
        putUser(keycloakSub, body, enabled ? "ユーザー有効化" : "ユーザー無効化");
    }

    /**
     * Keycloak側のユーザーを削除する。identity-service側での削除に追従させるために使う。
     * 404(既に存在しない)はDELETEの冪等性として正常扱いする。孤児検出(#562の
     * reconcileWithKeycloak)で既に無効化済みのユーザーを後から削除する場合など、
     * Keycloak側には既に何も無い状態で呼ばれることがあるため。
     */
    public void deleteUser(String keycloakSub) {
        try {
            adminClient.delete()
                    .uri("/users/{id}", keycloakSub)
                    .header("Authorization", "Bearer " + fetchAccessToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return;
            }
            throw new KeycloakUserSyncException(errorMessage("ユーザー削除(sub=" + keycloakSub + ")", e), e);
        } catch (Exception e) {
            throw connectionFailure("ユーザー削除(sub=" + keycloakSub + ")", e);
        }
    }

    /**
     * パスワード再設定を促すメールを送信する(#562の移行完了後、既存ユーザーに要求する
     * 「要パスワードリセット」状態からの再設定フロー)。realmのSMTP設定(smtpServer)が前提。
     */
    public void sendPasswordResetEmail(String keycloakSub) {
        ArrayNode actions = JsonNodeFactory.instance.arrayNode().add("UPDATE_PASSWORD");
        try {
            adminClient.put()
                    .uri("/users/{id}/execute-actions-email", keycloakSub)
                    .header("Authorization", "Bearer " + fetchAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(actions)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new KeycloakUserSyncException(
                    errorMessage("パスワード再設定メール送信(sub=" + keycloakSub + ")", e), e);
        } catch (Exception e) {
            throw connectionFailure("パスワード再設定メール送信(sub=" + keycloakSub + ")", e);
        }
    }

    /**
     * Keycloak側にsubに対応するユーザーが存在するかを確認する(#562の孤児検出)。
     * 404は「存在しない」として正常にfalseを返す。それ以外の失敗は接続不可等とみなし例外を投げる。
     */
    public boolean exists(String keycloakSub) {
        try {
            adminClient.get()
                    .uri("/users/{id}", keycloakSub)
                    .header("Authorization", "Bearer " + fetchAccessToken())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw new KeycloakUserSyncException(errorMessage("ユーザー存在確認(sub=" + keycloakSub + ")", e), e);
        } catch (Exception e) {
            throw connectionFailure("ユーザー存在確認(sub=" + keycloakSub + ")", e);
        }
    }

    private void putUser(String keycloakSub, ObjectNode body, String action) {
        try {
            adminClient.put()
                    .uri("/users/{id}", keycloakSub)
                    .header("Authorization", "Bearer " + fetchAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new KeycloakUserSyncException(errorMessage(action + "(sub=" + keycloakSub + ")", e), e);
        } catch (Exception e) {
            throw connectionFailure(action + "(sub=" + keycloakSub + ")", e);
        }
    }

    private synchronized String fetchAccessToken() {
        CachedToken current = this.cachedToken;
        if (current != null && !current.isExpiring()) {
            return current.accessToken();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());

        try {
            JsonNode response = tokenClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.has("access_token")) {
                throw new KeycloakUserSyncException("Keycloak管理APIのトークン取得レスポンスが不正です");
            }
            String accessToken = response.get("access_token").asText();
            long expiresInSeconds = response.path("expires_in").asLong(60);
            // 早めに更新して境界での失効を避ける(最低5秒は先読みしない)。
            long safetyMarginSeconds = Math.min(10, Math.max(expiresInSeconds - 5, 0));
            Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds - safetyMarginSeconds);
            CachedToken token = new CachedToken(accessToken, expiresAt);
            this.cachedToken = token;
            return accessToken;
        } catch (RestClientResponseException e) {
            throw new KeycloakUserSyncException(
                    "Keycloak管理APIのトークン取得に失敗しました(client_id=" + properties.getClientId() + "): "
                            + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (KeycloakUserSyncException e) {
            throw e;
        } catch (Exception e) {
            throw connectionFailure("トークン取得", e);
        }
    }

    private KeycloakUserSyncException connectionFailure(String action, Exception cause) {
        return new KeycloakUserSyncException(
                "Keycloak Admin APIの呼び出しに失敗しました(Keycloakが停止している可能性があります): "
                        + action + ": " + cause.getMessage(), cause);
    }

    private String errorMessage(String action, RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        if (status.value() == 401 || status.value() == 403) {
            return "Keycloak Admin APIの認証/認可に失敗しました。letsblog-servicesクライアントの"
                    + "サービスアカウントにmanage-usersロールが付与されているか確認してください: " + action;
        }
        if (status.value() == 409) {
            return "Keycloak側に同一のユーザー(email/username)が既に存在します: " + action;
        }
        return "Keycloak " + action + "に失敗しました: " + status + " " + e.getResponseBodyAsString();
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
        boolean isExpiring() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
