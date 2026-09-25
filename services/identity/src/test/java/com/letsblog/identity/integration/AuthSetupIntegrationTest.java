package com.letsblog.identity.integration;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.keycloak.KeycloakAdminClient;
import com.letsblog.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * issue #993: {@code POST /api/auth/setup}は公開エンドポイント(まだ誰もログインできない初回セットアップ
 * 導線のため、{@code SecurityConfig}の{@code PUBLIC_PATHS}に含まれる)だが、リクエストボディが
 * {@code @Valid}で弾かれた場合だけ401(認証エラー)を返していた。
 *
 * <p>原因は、{@code MethodArgumentNotValidException}をSpring Bootの{@code ErrorPageFilter}が
 * 実サーブレットコンテナ上で{@code DispatcherType.ERROR}として{@code /error}へ再ディスパッチし、
 * それが{@code anyRequest().authenticated()}の対象になっていたこと(Spring Security 6は既定で
 * 全dispatcher typeへ認可を適用する)。
 *
 * <p><b>なぜ実サーブレットコンテナ(RANDOM_PORT)なのか。</b> {@code MockMvc}はBootの
 * {@code ErrorPageFilter}によるERROR再ディスパッチを経由しないため、この不具合を再現できない
 * (実際に本クラスの前身をMockMvcで書いたところ、不具合を再現できず常に400が返った)。
 * CLAUDE.md(Test-First Implementation → Where the tests live)が認める、Web UIから到達できない
 * 契約に対するサービスレベルテストとしてここへ置く。
 *
 * <p>未認証のまま叩くことが前提の導線であるため、どのテストも{@code Authorization}ヘッダーを
 * 付与しない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("identity-service: POST /api/auth/setupの不正ボディに対する応答(issue #993)")
class AuthSetupIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        // 初回セットアップ導線のテストのため、既存ユーザーの有無を明示的に制御する
        // (UserWriteAuthorizationIntegrationTest等、他クラスが残した行の影響を受けないようにする)。
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("空ボディは未認証のまま400になり、どのフィールドが不正かを含む(認証エラーの401にならない)")
    void 空ボディは未認証のまま400になる() throws Exception {
        HttpResponse<String> response = post("{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("email");
        assertThat(response.body()).contains("password");
    }

    @Test
    @DisplayName("既にユーザーが存在する場合、整形済みボディは未認証のまま従来どおり400になる")
    void 整形済みボディでも既にユーザーがいれば400になる() throws Exception {
        User existing = new User();
        existing.setEmail("already-setup@example.test");
        existing.setKeycloakSub("sub-993-existing");
        existing.setPasswordHash("not-used-keycloak-handles-authentication");
        existing.setRole("admin");
        userRepository.save(existing);

        HttpResponse<String> response = post("{\"email\":\"new-admin@example.test\",\"password\":\"password123\"}");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("ユーザーが1人もいなければ、未認証のまま整形済みボディで初期管理者が作成される")
    void ユーザーがいなければ整形済みボディで管理者が作成される() throws Exception {
        when(keycloakAdminClient.createUser(eq("new-admin@example.test"), any(), any(), anyBoolean()))
                .thenReturn("sub-993-created");

        HttpResponse<String> response = post("{\"email\":\"new-admin@example.test\",\"password\":\"password123\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(userRepository.findByKeycloakSub("sub-993-created")).isPresent();
    }

    private HttpResponse<String> post(String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/auth/setup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
