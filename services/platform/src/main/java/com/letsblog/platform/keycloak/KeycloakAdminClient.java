package com.letsblog.platform.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.common.auth.ServiceTokenClient;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * platform-serviceからKeycloak Admin REST APIを呼び出す薄いクライアント(issue #693)。
 * legacy-api/identity-serviceの同名クラスと同じ実装だが、AdminPasswordResetRunner
 * (緊急パスワードリセット、HTTPリクエストコンテキストを持たない)が必要とする
 * {@link #findUserIdByEmail(String)}/{@link #setPassword(String, String)}のみを持つ
 * (createUser/deleteUserは初回セットアップ専用でlegacy-apiに残るUserService#setupInitialAdmin
 * のみが使うため、本サービスには不要)。
 *
 * <p>すべてのメソッドは、Keycloakへの到達不可・エラーレスポンスを問わず{@link KeycloakAdminException}
 * に変換して送出する(#681の受入基準: 対象ユーザーがKeycloak上に存在しない等の場合に、
 * 見かけ上成功したように振る舞わない)。
 */
public class KeycloakAdminClient {

    private final ServiceTokenClient serviceTokenClient;
    private final RestClient adminClient;

    /**
     * restClientBuilderには、接続断/無応答時に長時間ハングしないよう
     * タイムアウト付きのrequestFactoryを設定済みであることを期待する
     * (KeycloakAdminClientConfig#keycloakAdminRestClientBuilderが設定する)。
     */
    public KeycloakAdminClient(RestClient.Builder restClientBuilder, KeycloakAdminProperties properties) {
        this.serviceTokenClient = new ServiceTokenClient(
                restClientBuilder, properties.getTokenUri(), properties.getClientId(), properties.getClientSecret());

        RestClient.Builder adminBuilder = restClientBuilder.clone().baseUrl(properties.getAdminBaseUri());
        preferJackson2(adminBuilder);
        this.adminClient = adminBuilder.build();
    }

    /**
     * Boot 4ではRestClientの既定JSONコンバータがJackson3(tools.jackson)になったが、本クラスは
     * com.fasterxml.jackson.databind.JsonNode(Jackson2)でレスポンスを組み立てている
     * (LegacyJacksonRestClientConfigと同じ理由)。
     */
    private static void preferJackson2(RestClient.Builder builder) {
        builder.messageConverters(converters -> {
            converters.removeIf(AbstractJacksonHttpMessageConverter.class::isInstance);
            converters.add(0, new MappingJackson2HttpMessageConverter());
        });
    }

    /**
     * 管理者が指定した平文パスワードを即時設定する(temporary=false、次回ログイン時の強制変更なし)。
     */
    public void setPassword(String keycloakSub, String newPassword) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("type", "password");
        body.put("value", newPassword);
        body.put("temporary", false);
        try {
            adminClient.put()
                    .uri("/users/{id}/reset-password", keycloakSub)
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new KeycloakAdminException(errorMessage("パスワード即時設定(sub=" + keycloakSub + ")", e), e);
        } catch (Exception e) {
            throw connectionFailure("パスワード即時設定(sub=" + keycloakSub + ")", e);
        }
    }

    /**
     * メールアドレスからKeycloak側のユーザーID(sub)を検索する(AdminPasswordResetRunnerが
     * 対象ユーザーを特定するために使う)。該当なしはOptional.emptyを返す
     * (=対象ユーザーがKeycloak上に存在しない)。
     */
    public Optional<String> findUserIdByEmail(String email) {
        try {
            JsonNode result = adminClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/users")
                            .queryParam("email", email)
                            .queryParam("exact", "true")
                            .build())
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
                    .retrieve()
                    .body(JsonNode.class);
            if (result == null || !result.isArray() || result.isEmpty()) {
                return Optional.empty();
            }
            JsonNode idNode = result.get(0).get("id");
            return idNode == null ? Optional.empty() : Optional.of(idNode.asText());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new KeycloakAdminException(errorMessage("メールアドレスによるユーザー検索(email=" + email + ")", e), e);
        } catch (Exception e) {
            throw connectionFailure("メールアドレスによるユーザー検索(email=" + email + ")", e);
        }
    }

    /**
     * Keycloakのユーザー/レルム/鍵キャッシュ(Infinispan)を無効化する(issue #1590)。バックアップ復元は
     * KeycloakのPostgreSQLを直接書き換えるため、稼働中のKeycloakが復元前のユーザー情報を返し続けるのを防ぐ。
     * 3つのエンドポイントを順に呼び、いずれかが失敗したら{@link KeycloakAdminException}を送出する。
     */
    public void clearCaches() {
        String action = "キャッシュの無効化";
        try {
            String token = serviceTokenClient.getAccessToken();
            for (String path : new String[] {"/clear-user-cache", "/clear-realm-cache", "/clear-keys-cache"}) {
                adminClient.post()
                        .uri(path)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .toBodilessEntity();
            }
        } catch (RestClientResponseException e) {
            throw new KeycloakAdminException(errorMessage(action, e), e);
        } catch (Exception e) {
            throw connectionFailure(action, e);
        }
    }

    private KeycloakAdminException connectionFailure(String action, Exception cause) {
        return new KeycloakAdminException(
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
}
