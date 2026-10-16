package com.letsblog.identity.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Keycloak Admin APIの向き先をサーバー側フェイクにする{@code @SpringBootTest}の共通の親(issue #1826)。
 *
 * <p>{@code @DynamicPropertySource}のメソッドがテストクラスごとに別だと、Springはそれを別のコンテキスト設定と
 * みなして、クラスごとにコンテキスト(=DB接続プール)を作る。DBの接続数には上限があるため、メソッドをこの親に
 * 1つだけ置き、同じ設定のクラスがコンテキストを共有できるようにする。
 */
public abstract class KeycloakFakeSupport {

    /** 共有のKeycloakフェイク。各テストは{@code @BeforeEach}で{@link FakeKeycloak#reset()}する。 */
    protected final FakeKeycloak keycloak = SharedFakeKeycloak.get();

    @DynamicPropertySource
    static void keycloakAdminApi(DynamicPropertyRegistry registry) {
        SharedFakeKeycloak.register(registry);
    }
}
