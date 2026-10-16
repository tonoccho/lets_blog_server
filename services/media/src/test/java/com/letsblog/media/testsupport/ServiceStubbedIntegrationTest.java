package com.letsblog.media.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 実DB(ADR-0006の実MySQL)と実物の{@code *Client}で起動する{@code @SpringBootTest}の共通の土台(issue #1820)。
 *
 * <p>外部サービスの接続先は{@link ServiceStubs}のフェイクHTTPサーバーへ向ける。差し替える({@code @MockitoBean})のは、
 * Keycloakの鍵取得を伴う{@link JwtDecoder}だけ。設定を1か所に置くのは、Springのコンテキストキャッシュがクラスごとの
 * {@code @DynamicPropertySource}を別のキーとして数え、サブクラスごとにコンテキスト(とDB接続プール)が増えて
 * MySQLの接続数の上限に当たるのを避けるため。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ServiceStubbedIntegrationTest {

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        ServiceStubs.register(registry);
    }

    @BeforeEach
    void resetServiceStubs() {
        ServiceStubs.reset();
    }
}
