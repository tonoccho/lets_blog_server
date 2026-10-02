package com.letsblog.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.common.auth.ServiceTokenClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.client.RestClient;

/**
 * GenerationJobClientがオプトイン({@code @Import})で登録できること(#1483、#1250の再発防止)。
 *
 * <p>#1250はGenerationJobClientのコンストラクタ解決失敗でSpring Bootの統合テストが全滅した件。
 * 共通化後も{@code @Import}だけで、2つのコンストラクタがあってもBeanとして生成できることを固定する。
 */
class GenerationJobClientWiringTest {

    private AnnotationConfigApplicationContext context(boolean withTokenClient) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("app.ai-service-uri", "http://127.0.0.1:1")));
        context.registerBean(RestClient.Builder.class, () -> RestClient.builder());
        if (withTokenClient) {
            context.registerBean(ServiceTokenClient.class,
                    () -> new ServiceTokenClient(RestClient.builder(), "http://127.0.0.1:1/t", "id", "secret"));
        }
        context.register(Importer.class);
        return context;
    }

    @org.springframework.context.annotation.Import(GenerationJobClient.class)
    static class Importer {
    }

    @Test
    @DisplayName("@Importするとapp.ai-service-uriとServiceTokenClientからBeanが生成される")
    void importでBeanが生成される() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            context.refresh();

            assertThat(context.getBean(GenerationJobClient.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("ServiceTokenClientが無くてもBeanは生成できる(log-writerはlistのみ使う)")
    void tokenClientが無くてもBeanは生成できる() {
        try (AnnotationConfigApplicationContext context = context(false)) {
            context.refresh();

            assertThat(context.getBean(GenerationJobClient.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("ServiceTokenClientが無い状態でupdateStatusを呼ぶとBean不在が明確に分かる")
    void tokenClientが無いとupdateStatusは失敗する() {
        try (AnnotationConfigApplicationContext context = context(false)) {
            context.refresh();
            GenerationJobClient client = context.getBean(GenerationJobClient.class);

            assertThatThrownBy(() -> client.updateStatus(1L, "running", "{}"))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    @Test
    @DisplayName("@Importしないコンテキストにはai-serviceクライアントが生えない")
    void importしなければBeanは無い() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();

            assertThat(context.getBeanNamesForType(GenerationJobClient.class)).isEmpty();
        }
    }
}
