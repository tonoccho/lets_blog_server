package com.letsblog.platform.cli;

import com.letsblog.platform.keycloak.KeycloakAdminClient;
import com.letsblog.platform.keycloak.KeycloakAdminException;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AdminPasswordResetRunnerの回帰テスト(issue #693)。legacy-api版と異なり、本サービスは
 * KeycloakAdminClientのみに依存する(UserServiceのようなローカルDBアクセスは行わない、
 * クラスのJavadoc参照)。run()自体はSystem.exitを呼ぶため、privateのresetPassword(String, String)を
 * リフレクションで直接呼び出して振る舞いを検証する。
 */
@ExtendWith(MockitoExtension.class)
class AdminPasswordResetRunnerTest {

    @Mock
    private KeycloakAdminClient keycloakAdminClient;
    @Mock
    private ConfigurableApplicationContext context;

    @Test
    void 環境変数が未設定ならKeycloakを呼ばずに失敗する() throws Exception {
        AdminPasswordResetRunner runner = new AdminPasswordResetRunner(keycloakAdminClient, context);

        int exitCode = invokeResetPassword(runner, null, null);

        assertEquals(1, exitCode);
        verifyNoInteractions(keycloakAdminClient);
    }

    @Test
    void パスワードが8文字未満なら失敗する() throws Exception {
        AdminPasswordResetRunner runner = new AdminPasswordResetRunner(keycloakAdminClient, context);

        int exitCode = invokeResetPassword(runner, "admin@example.com", "short");

        assertEquals(1, exitCode);
        verifyNoInteractions(keycloakAdminClient);
    }

    @Test
    void keycloak上にユーザーが見つからなければ失敗する() throws Exception {
        when(keycloakAdminClient.findUserIdByEmail("admin@example.com")).thenReturn(Optional.empty());
        AdminPasswordResetRunner runner = new AdminPasswordResetRunner(keycloakAdminClient, context);

        int exitCode = invokeResetPassword(runner, "admin@example.com", "new-password-123");

        assertEquals(1, exitCode);
        verify(keycloakAdminClient, never()).setPassword(anyString(), anyString());
    }

    @Test
    void keycloak上でパスワード変更に成功すれば0を返す() throws Exception {
        when(keycloakAdminClient.findUserIdByEmail("admin@example.com")).thenReturn(Optional.of("kc-sub-1"));
        AdminPasswordResetRunner runner = new AdminPasswordResetRunner(keycloakAdminClient, context);

        int exitCode = invokeResetPassword(runner, "admin@example.com", "new-password-123");

        assertEquals(0, exitCode);
        verify(keycloakAdminClient, times(1)).setPassword("kc-sub-1", "new-password-123");
    }

    @Test
    void keycloak呼び出しが例外を投げれば失敗する() throws Exception {
        when(keycloakAdminClient.findUserIdByEmail("admin@example.com"))
                .thenThrow(new KeycloakAdminException("Keycloakに到達できません"));
        AdminPasswordResetRunner runner = new AdminPasswordResetRunner(keycloakAdminClient, context);

        int exitCode = invokeResetPassword(runner, "admin@example.com", "new-password-123");

        assertEquals(1, exitCode);
    }

    /** privateのresetPassword(String, String)をリフレクションで直接呼び出す(System.exitを避けるため)。 */
    private int invokeResetPassword(AdminPasswordResetRunner runner, String email, String password)
            throws Exception {
        Method method = AdminPasswordResetRunner.class.getDeclaredMethod("resetPassword", String.class, String.class);
        method.setAccessible(true);
        return (int) method.invoke(runner, email, password);
    }
}
