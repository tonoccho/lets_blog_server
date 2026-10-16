package com.letsblog.ai.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 他サービスのHTTPフェイク({@link IntegrationBackends})へ向ける{@code @SpringBootTest}の共通の親(issue #1824)。
 *
 * <p>{@code @DynamicPropertySource}をこの1か所に置くのは、Springのコンテキストキャッシュのキーに
 * 「どのメソッドが動的プロパティを登録するか」が含まれるため。クラスごとに同じメソッドを宣言すると、
 * 設定が同じでもクラスごとにコンテキスト(とDBの接続プール)が作られ、MySQLの接続数を使い切る。
 */
public abstract class BackendIntegrationTest {

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        IntegrationBackends.register(registry);
    }
}
