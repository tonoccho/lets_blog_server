package com.letsblog.content.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.content.service.IdentityServiceUnavailableException;
import com.letsblog.content.testsupport.FakeHttpServer;
import com.letsblog.content.testsupport.FakeHttpServer.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/** identity-service への内部呼び出しの分岐。クライアントは実物、identity-service は HTTP のサーバー側フェイク。 */
class IdentityBridgeClientBranchTest {

    private FakeHttpServer http;
    private IdentityBridgeClient client;

    @BeforeEach
    void setUp() {
        http = new FakeHttpServer();
        client = new IdentityBridgeClient(RestClient.builder(), http.baseUrl());
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void メンバー判定はtrueだけをメンバーとし_本文が空ならメンバーではない() {
        http.json("GET", "/api/internal/identity/projects/1/members/2", "true");
        assertThat(client.isProjectMember(1L, 2L, "Bearer t")).isTrue();

        http.json("GET", "/api/internal/identity/projects/1/members/2", "false");
        assertThat(client.isProjectMember(1L, 2L, "Bearer t")).isFalse();

        http.on("GET", "/api/internal/identity/projects/1/members/2", Response.empty(200));
        assertThat(client.isProjectMember(1L, 2L, "Bearer t")).isFalse();
    }

    @Test
    void メンバー判定に失敗したら連携不可の例外にする() {
        http.status("GET", "/api/internal/identity/projects/1/members/2", 500, "{}");

        assertThatThrownBy(() -> client.isProjectMember(1L, 2L, "Bearer t"))
                .isInstanceOf(IdentityServiceUnavailableException.class)
                .hasMessageContaining("メンバー判定");
    }

    @Test
    void ロール一覧の応答が空なら空のリスト() {
        http.on("GET", "/api/internal/identity/roles", Response.empty(200));

        assertThat(client.listRoles("Bearer t")).isEmpty();
    }

    @Test
    void ロール一覧を返し_失敗したら連携不可の例外にする() {
        http.json("GET", "/api/internal/identity/roles", "[{\"roleName\":\"admin\",\"displayName\":\"管理者\"}]");
        assertThat(client.listRoles("Bearer t")).hasSize(1);

        http.status("GET", "/api/internal/identity/roles", 500, "{}");
        assertThatThrownBy(() -> client.listRoles("Bearer t"))
                .isInstanceOf(IdentityServiceUnavailableException.class)
                .hasMessageContaining("ロール一覧");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void トークンがnullか空白ならAuthorizationを付けない(String token) {
        http.json("GET", "/api/internal/identity/roles", "[]");

        client.listRoles(token);

        assertThat(http.last().header("Authorization")).isNull();
    }

    @Test
    void トークンがあればそのままAuthorizationへ転送する() {
        http.json("GET", "/api/internal/identity/roles", "[]");

        client.listRoles("Bearer abc");

        assertThat(http.last().header("Authorization")).isEqualTo("Bearer abc");
    }
}
