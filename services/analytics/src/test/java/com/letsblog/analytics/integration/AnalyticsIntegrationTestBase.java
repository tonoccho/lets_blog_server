package com.letsblog.analytics.integration;

import com.letsblog.analytics.testsupport.FakeBackends;
import com.letsblog.analytics.testsupport.FakeHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Springコンテキストを起動する統合テストの共通の親(issue #1827)。
 *
 * <p>外部との通信だけを差し替える。{@code IdentityClient}・{@code IdentityBridgeClient}・{@code ProjectBridgeClient}・
 * {@code CurrentActorService}などの自前のクラスは実物のBeanのまま、ベースURLを<b>サーバー側フェイクのHTTP</b>
 * ({@link #HTTP})へ向ける。{@code JwtDecoder}は Keycloak の鍵取得(JWKSへのHTTP)を伴うため{@code @MockitoBean}で
 * 差し替える。
 *
 * <p>{@code @DynamicPropertySource}をテストクラスごとに持つと、Springがそれぞれを別のコンテキスト設定とみなして
 * DBの接続プールをクラスごとに作り、MySQLの接続上限を超えるため、この親に1つだけ置く。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AnalyticsIntegrationTestBase {

    protected static final FakeHttpServer HTTP = new FakeHttpServer();
    protected static final FakeBackends BACKENDS = new FakeBackends(HTTP);
    private static final int DEFAULT_ROUTES = HTTP.routeCount();

    @Autowired
    protected MockMvc mockMvc;

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void externalHttp(DynamicPropertyRegistry registry) {
        registry.add("app.identity-service-uri", HTTP::baseUrl);
        registry.add("app.project-service-uri", HTTP::baseUrl);
    }

    @BeforeEach
    void resetFakeBackends() {
        HTTP.clearRoutesAfter(DEFAULT_ROUTES);
        BACKENDS.reset();
    }
}
