package com.letsblog.identity.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * {@code @SpringBootTest}が共有する、Keycloak(トークンエンドポイントとAdmin REST API)のサーバー側フェイク
 * (issue #1826)。Spring のコンテキストはクラスをまたいで使い回されるため、{@code KeycloakAdminClient}が向く先も
 * JVMで1つに固定する。{@code KeycloakAdminClient}をMockitoで差し替える代わりに、本物のクライアントが
 * 実際にHTTPでこのサーバーへ送る。各テストは{@code @BeforeEach}で{@link FakeKeycloak#reset()}する。
 */
public final class SharedFakeKeycloak {

    private static final FakeKeycloak INSTANCE = new FakeKeycloak();

    private SharedFakeKeycloak() {
    }

    public static FakeKeycloak get() {
        return INSTANCE;
    }

    /** {@code @DynamicPropertySource}から呼び、Admin APIとトークンエンドポイントの向き先をフェイクにする。 */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("keycloak.admin.token-uri", () -> INSTANCE.properties().getTokenUri());
        registry.add("keycloak.admin.admin-base-uri", () -> INSTANCE.properties().getAdminBaseUri());
    }
}
