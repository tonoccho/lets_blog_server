package com.letsblog.api.keycloak;

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
 * legacy-apiからKeycloak Admin REST APIを呼び出す薄いクライアント(#681)。
 *
 * <p>identity-serviceに既に同等の実装(services/identity/.../keycloak/KeycloakAdminClient、#562)が
 * あるが、サービス間はGradleモジュールではなくHTTPでのみ連携する方針(settings.gradle参照。
 * どのserviceのbuild.gradleも他serviceのプロジェクトには依存していない)であり、かつ
 * identity-serviceのユーザー管理API(/api/users等)は認証済みJWTを持つ呼び出し元を前提にしているため
 * (AdminAuthorizationServiceがJWTから解決したactorのadmin権限を要求する)、初回セットアップ
 * (/api/auth/setup、まだ誰もログインできない状態で呼ばれる)や運用CLI(AdminPasswordResetRunner、
 * HTTPリクエストコンテキストを持たない)からは利用できない。そのため、identity-serviceと同じ
 * "letsblog-services"クライアントクレデンシャルズグラント(ServiceTokenClient、#567でlbs-commonに
 * 一般化済み)を使い、同じ流儀のクライアントをこのモジュール内に個別に持つ。
 *
 * <p>すべてのメソッドは、Keycloakへの到達不可・エラーレスポンスを問わず{@link KeycloakAdminException}
 * に変換して送出する(#681の受入基準: 対象ユーザーがKeycloak上に存在しない等の場合に、
 * DBのみを操作して見かけ上成功したように振る舞わない)。
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
     * com.fasterxml.jackson.databind.JsonNode/ObjectNode(Jackson2)でリクエスト/レスポンスを組み立てている
     * (LegacyJacksonRestClientConfig参照)。
     */
    private static void preferJackson2(RestClient.Builder builder) {
        builder.messageConverters(converters -> {
            converters.removeIf(AbstractJacksonHttpMessageConverter.class::isInstance);
            converters.add(0, new MappingJackson2HttpMessageConverter());
        });
    }

    /**
     * Keycloakにユーザーを新規作成し、生成されたsub(ユーザーID)を返す。初回セットアップ専用のため、
     * requiredActionsは要求しない(パスワードは{@link #setPassword(String, String)}で即時設定する)。
     */
    public String createUser(String email) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("username", email);
        body.put("email", email);
        body.put("enabled", true);
        body.put("emailVerified", true);

        try {
            HttpHeaders headers = adminClient.post()
                    .uri("/users")
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders();
            String location = headers.getFirst(HttpHeaders.LOCATION);
            if (location == null || location.isBlank()) {
                throw new KeycloakAdminException(
                        "Keycloakユーザー作成のレスポンスからIDを取得できませんでした(email=" + email + ")");
            }
            return location.substring(location.lastIndexOf('/') + 1);
        } catch (RestClientResponseException e) {
            throw new KeycloakAdminException(errorMessage("ユーザー作成(email=" + email + ")", e), e);
        } catch (KeycloakAdminException e) {
            throw e;
        } catch (Exception e) {
            throw connectionFailure("ユーザー作成(email=" + email + ")", e);
        }
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
     * メールアドレスからKeycloak側のユーザーID(sub)を検索する(AdminPasswordResetRunnerが、
     * ローカルにkeycloak_sub未設定のユーザーに対して対象を特定するために使う)。
     * 該当なしはOptional.emptyを返す(=対象ユーザーがKeycloak上に存在しない)。
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
     * Keycloak側のユーザーを削除する。setPassword失敗等でローカル保存を中断した際に、
     * 孤児となったKeycloakユーザーをベストエフォートで取り除くために使う。
     * 404(既に存在しない)はDELETEの冪等性として正常扱いする。
     */
    public void deleteUser(String keycloakSub) {
        try {
            adminClient.delete()
                    .uri("/users/{id}", keycloakSub)
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return;
            }
            throw new KeycloakAdminException(errorMessage("ユーザー削除(sub=" + keycloakSub + ")", e), e);
        } catch (Exception e) {
            throw connectionFailure("ユーザー削除(sub=" + keycloakSub + ")", e);
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
