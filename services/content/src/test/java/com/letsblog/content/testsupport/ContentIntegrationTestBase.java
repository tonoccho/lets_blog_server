package com.letsblog.content.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Spring Bootを起動してMockMvcで叩く結合テストの共通の土台(issue #1822)。
 *
 * <p>自前のBean({@code IdentityClient}・{@code IdentityBridgeClient}・{@code ProjectBridgeClient}・各サービス)は
 * すべて実物。差し替えるのは外部との通信だけ: DBは実MySQL(ADR-0006)、他サービスのHTTPは{@link ExternalServices}、
 * Keycloakの鍵取得を伴う{@link JwtDecoder}はMockito。全サブクラスが同じ設定なので、Springのコンテキストを共有する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
public abstract class ContentIntegrationTestBase {

    /** Keycloakの公開鍵取得(外部との通信)を伴うため差し替える。 */
    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        ExternalServices.register(registry);
    }

    @BeforeEach
    void resetExternalServices() {
        ExternalServices.reset();
    }
}
