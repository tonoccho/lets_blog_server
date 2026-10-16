package com.letsblog.platform.cli;

import com.letsblog.platform.keycloak.KeycloakAdminClient;
import com.letsblog.platform.testsupport.FakeHttpServer;
import com.letsblog.platform.testsupport.KeycloakFakes;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AdminPasswordResetRunnerの回帰テスト(issue #693)。legacy-api版と異なり、本サービスは
 * KeycloakAdminClientのみに依存する(UserServiceのようなローカルDBアクセスは行わない、
 * クラスのJavadoc参照)。run()自体はSystem.exitを呼ぶため、privateのresetPassword(String, String)を
 * リフレクションで直接呼び出して振る舞いを検証する。
 *
 * <p>issue #1825: KeycloakAdminClientは実物で、KeycloakのHTTP(トークンとAdmin API)だけをサーバー側のフェイクで模す。
 */
class AdminPasswordResetRunnerTest {

    private FakeHttpServer http;
    private AdminPasswordResetRunner runner;

    @BeforeEach
    void setUp() {
        http = new FakeHttpServer();
        KeycloakAdminClient client = KeycloakFakes.client(http);
        runner = new AdminPasswordResetRunner(client, new GenericApplicationContext());
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void 環境変数が未設定ならKeycloakを呼ばずに失敗する() throws Exception {
        assertEquals(1, invokeResetPassword(null, null));
        assertEquals(1, invokeResetPassword(" ", "new-password-123"));
        assertEquals(1, invokeResetPassword("admin@example.com", null));
        assertEquals(1, invokeResetPassword("admin@example.com", "  "));
        assertTrue(http.requests().isEmpty());
    }

    @Test
    void パスワードが8文字未満なら失敗する() throws Exception {
        assertEquals(1, invokeResetPassword("admin@example.com", "short"));
        assertTrue(http.requests().isEmpty());
    }

    @Test
    void keycloak上にユーザーが見つからなければ失敗する() throws Exception {
        http.json("GET", "/admin/users", "[]");

        assertEquals(1, invokeResetPassword("admin@example.com", "new-password-123"));
        assertTrue(http.requests("PUT", ".*reset-password").isEmpty());
    }

    @Test
    void keycloak上でパスワード変更に成功すれば0を返す() throws Exception {
        http.json("GET", "/admin/users", "[{\"id\":\"kc-sub-1\"}]");
        http.on("PUT", "/admin/users/kc-sub-1/reset-password", FakeHttpServer.Response.empty(204));

        assertEquals(0, invokeResetPassword("admin@example.com", "new-password-123"));

        FakeHttpServer.Request put = http.requests("PUT", ".*reset-password").get(0);
        assertEquals("Bearer kc-token", put.header("Authorization"));
        assertTrue(put.bodyText().contains("\"value\":\"new-password-123\""), put.bodyText());
        assertTrue(put.bodyText().contains("\"temporary\":false"), put.bodyText());
    }

    @Test
    void keycloak呼び出しが例外を投げれば失敗する() throws Exception {
        http.status("GET", "/admin/users", 500, "{}");

        assertEquals(1, invokeResetPassword("admin@example.com", "new-password-123"));
    }

    /** privateのresetPassword(String, String)をリフレクションで直接呼び出す(System.exitを避けるため)。 */
    private int invokeResetPassword(String email, String password) throws Exception {
        Method method = AdminPasswordResetRunner.class.getDeclaredMethod("resetPassword", String.class, String.class);
        method.setAccessible(true);
        return (int) method.invoke(runner, email, password);
    }
}
