package com.letsblog.identity.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.common.auth.ServiceTokenClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * identity-serviceからKeycloak Admin REST APIを呼び出す薄いクライアント(#562)。
 * GithubClient/AdSenseClient等と同様、専用SDKは導入せずRestClientの薄いラッパーとして実装する。
 *
 * <p>アクセストークンの取得・キャッシュ・期限前更新は{@link ServiceTokenClient}(#567でlbs-commonに
 * 一般化された共通部品)に委譲する。元々このクラスに実装していたロジック(#562)と同じ
 * {@code letsblog-services}クライアントクレデンシャルズグラントを使い、挙動は変わらない
 * (併せて連続失敗時のサーキットブレーカーが働くようになる)。
 *
 * <p>すべてのメソッドは、Keycloakへの到達不可・エラーレスポンスを問わず{@link KeycloakUserSyncException}
 * に変換して送出する(#562の受入基準: Keycloak停止時に明確なエラーを返し、暗黙に成功しない)。
 */
public class KeycloakAdminClient {

    /** 割当済みのrealmロール一覧(直接付与されているものだけ。composite展開はしない)。 */
    private static final String ASSIGNED_REALM_ROLES = "/users/{id}/role-mappings/realm";

    /** まだ割り当てられていない、割り当て可能なrealmロール一覧。 */
    private static final String AVAILABLE_REALM_ROLES = "/users/{id}/role-mappings/realm/available";

    private final ServiceTokenClient serviceTokenClient;
    private final RestClient adminClient;

    /**
     * restClientBuilderには、接続断/無応答時に長時間ハングしないよう
     * タイムアウト付きのrequestFactoryを設定済みであることを期待する
     * (本番ではKeycloakAdminClientConfig#keycloakAdminRestClientBuilderが設定する。
     * GithubClientConfigと同様、requestFactoryの選定はクライアント自身ではなく呼び出し側の
     * 責務とすることで、テストでMockRestServiceServerを差し込めるようにしている)。
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
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
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
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
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
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
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
     * 管理者が指定した平文パスワードを即時設定する(temporary=false、次回ログイン時の強制変更なし)。
     * {@link #sendPasswordResetEmail(String)}(セルフサービスの再設定メール送信)とは別用途であり、
     * legacy-apiの初回セットアップ(/api/auth/setup)・緊急復旧(AdminPasswordResetRunner)導線が
     * 呼び出す想定(#681)。legacy-apiはこのモジュールに依存できないため、legacy-api側には
     * 同じ流儀の別クライアント実装(services/legacy-api/.../keycloak/KeycloakAdminClient)を置く。
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
            throw new KeycloakUserSyncException(errorMessage("パスワード即時設定(sub=" + keycloakSub + ")", e), e);
        } catch (Exception e) {
            throw connectionFailure("パスワード即時設定(sub=" + keycloakSub + ")", e);
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
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
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

    /**
     * realmロールを付与する(issue #955)。既に付与済みなら何もしないため冪等。
     *
     * <p>Keycloakのロールマッピングは「ロールの表現({@code id}+{@code name})の配列」を要求するので、
     * 先にロールの{@code id}を引く必要がある。ここで{@code GET /roles/&#123;roleName&#125;}を
     * <b>使わない</b>のは、そのエンドポイントが{@code realm-management}の{@code view-realm}権限を
     * 要求するためである。{@code letsblog-services}のサービスアカウントに付いているのは
     * {@code manage-users}と{@code view-users}だけで(keycloak/realm-export.json)、
     * 実機で確認すると{@code GET /roles/admin}は403 Forbiddenになる。
     * 代わりにユーザースコープの{@code role-mappings/realm}と{@code role-mappings/realm/available}を
     * 使う。こちらは{@code view-users}で読めるため、realmの権限設定を変えずに済む
     * (= 既に構築済みの環境がrealmの再インポートを迫られない)。
     *
     * <p>{@code available}は「まだ割り当てられていない、割り当て可能なロール」しか返さない。
     * そのため先に割当済み一覧を見て、既に付いていればそこで終える。
     *
     * <p>どちらの一覧にも無い場合は例外にする。付与できていないのに成功として返すと、
     * ローカルDBだけがadminでKeycloakが追随しない状態(issue #955そのもの)が再発するため。
     */
    public void grantRealmRole(String keycloakSub, String roleName) {
        if (findRoleMapping(keycloakSub, roleName, ASSIGNED_REALM_ROLES) != null) {
            return;
        }
        ObjectNode role = findRoleMapping(keycloakSub, roleName, AVAILABLE_REALM_ROLES);
        if (role == null) {
            throw new KeycloakUserSyncException(
                    "Keycloakのrealmロール '" + roleName + "' をユーザー(sub=" + keycloakSub + ")へ"
                            + "割り当てられません。realmにそのロールが定義されているか"
                            + "(keycloak/realm-export.json)確認してください(issue #955)。");
        }
        ArrayNode body = JsonNodeFactory.instance.arrayNode();
        body.add(role);
        modifyRealmRoleMappings(
                HttpMethod.POST, keycloakSub, body, "realmロール付与(role=" + roleName + ")");
    }

    /**
     * realmロールを剥奪する(issue #955)。割当済み一覧に無ければ何もしないため冪等。
     *
     * <p>剥奪したい状態が既に満たされている場合(ロールが付いていない、realmにロールが無い)は
     * 正常扱いにする。付与と違い、「消えていること」が目的なので、消す対象が無いのは成功である。
     */
    public void revokeRealmRole(String keycloakSub, String roleName) {
        ObjectNode role = findRoleMapping(keycloakSub, roleName, ASSIGNED_REALM_ROLES);
        if (role == null) {
            return;
        }
        ArrayNode body = JsonNodeFactory.instance.arrayNode();
        body.add(role);
        modifyRealmRoleMappings(
                HttpMethod.DELETE, keycloakSub, body, "realmロール剥奪(role=" + roleName + ")");
    }

    /**
     * 指定した一覧({@code uri})から{@code roleName}を探し、ロールマッピングに渡せる表現
     * ({@code id}/{@code name})を返す。見つからなければ{@code null}を返し、
     * 「無いこと」をどう扱うかは呼び出し側(付与=例外、剥奪=正常)に委ねる。
     *
     * <p>ユーザー自体が存在しない場合(404)も{@code null}を返す。剥奪は目的が達成済みなので正常、
     * 付与は上の呼び出し側が「割り当てられない」として例外にするため、いずれも安全側に倒れる。
     */
    private ObjectNode findRoleMapping(String keycloakSub, String roleName, String uri) {
        String action = "realmロール一覧取得(role=" + roleName + ", sub=" + keycloakSub + ")";
        JsonNode roles;
        try {
            roles = adminClient.get()
                    .uri(uri, keycloakSub)
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw new KeycloakUserSyncException(errorMessage(action, e), e);
        } catch (Exception e) {
            throw connectionFailure(action, e);
        }
        if (roles == null || !roles.isArray()) {
            return null;
        }
        for (JsonNode role : roles) {
            if (roleName.equals(role.path("name").asText(null)) && role.hasNonNull("id")) {
                ObjectNode mapping = JsonNodeFactory.instance.objectNode();
                mapping.put("id", role.get("id").asText());
                mapping.put("name", roleName);
                return mapping;
            }
        }
        return null;
    }

    private void modifyRealmRoleMappings(
            HttpMethod httpMethod, String keycloakSub, ArrayNode body, String action) {
        try {
            adminClient.method(httpMethod)
                    .uri("/users/{id}/role-mappings/realm", keycloakSub)
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
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

    private void putUser(String keycloakSub, ObjectNode body, String action) {
        try {
            adminClient.put()
                    .uri("/users/{id}", keycloakSub)
                    .header("Authorization", "Bearer " + serviceTokenClient.getAccessToken())
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
}
