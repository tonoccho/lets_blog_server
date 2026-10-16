package com.letsblog.platform.integration;

import com.letsblog.platform.testsupport.SharedIdentityFake;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 統合テスト(@SpringBootTest)の共通の土台(issue #1825)。identity-serviceの接続先を、全クラスで共有する
 * サーバー側のフェイク({@link SharedIdentityFake})へ向ける。@DynamicPropertySourceをここに1つだけ置くことで、
 * これを継承するテストクラスがSpringのコンテキストのキャッシュを共有できる(クラスごとに置くと別のコンテキストになり、
 * DB接続を使い切る)。
 */
abstract class IdentityFakeIntegrationTestBase {

    @DynamicPropertySource
    static void identityService(DynamicPropertyRegistry registry) {
        registry.add("app.identity-service-uri", SharedIdentityFake::baseUrl);
    }
}
