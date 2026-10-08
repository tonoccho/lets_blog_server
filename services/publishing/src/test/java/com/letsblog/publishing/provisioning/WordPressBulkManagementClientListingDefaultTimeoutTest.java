package com.letsblog.publishing.provisioning;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * issue #1428: 実機のprovision-agentの{@code /themes} {@code /plugins}は1回約4秒かかり、
 * 旧既定値(5秒)では負荷時にリードタイムアウトして空一覧になる(比較表で「未インストール」になる)。
 * 本番の{@code application.yml}の既定値(環境変数を設定しない状態)をそのまま読み、
 * 旧既定値を超えて応答するagentのスタブから{@code listThemes}/{@code listPlugins}が一覧を
 * 返せることを確かめる。環境変数による上書きは{@link WordPressBulkManagementClientEnvOverrideTest}が見る。
 */
class WordPressBulkManagementClientListingDefaultTimeoutTest {

    /** 旧既定値(5秒)を超える応答時間。 */
    private static final long SLOW_RESPONSE_MS = 5_500;

    private HttpServer httpServer;
    private ExecutorService serverExecutor;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void application_ymlの既定値のlistingクライアントは旧既定値を超えて応答するagentからテーマ一覧を取得できる() throws IOException {
        startSlowAgent();

        List<WordPressBulkManagementClient.PluginThemeInfo> themes = clientWithApplicationYmlDefaults().listThemes("site-a");

        assertEquals(List.of(new WordPressBulkManagementClient.PluginThemeInfo("twentytwentyfour", "active")), themes);
    }

    @Test
    void application_ymlの既定値のlistingクライアントは旧既定値を超えて応答するagentからプラグイン一覧を取得できる() throws IOException {
        startSlowAgent();

        List<WordPressBulkManagementClient.PluginThemeInfo> plugins = clientWithApplicationYmlDefaults().listPlugins("site-a");

        assertEquals(List.of(new WordPressBulkManagementClient.PluginThemeInfo("akismet", "inactive")), plugins);
    }

    private WordPressBulkManagementClient clientWithApplicationYmlDefaults() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        StandardEnvironment environment = new StandardEnvironment();
        // 開発機に環境変数が設定されていても「既定値」を見るため、実環境変数は読まない。
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("stubAgent", Map.of(
                "app.wordpress-provision-base-url", baseUrl(),
                "app.wordpress-provision-token", "token")));
        context.setEnvironment(environment);

        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setEnvironment(environment);
        configurer.setProperties(yaml.getObject());
        context.addBeanFactoryPostProcessor(configurer);

        context.registerBean(WordPressBulkManagementClient.class);
        context.refresh();
        WordPressBulkManagementClient client = context.getBean(WordPressBulkManagementClient.class);
        context.close();
        return client;
    }

    private void startSlowAgent() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(4);
        httpServer.setExecutor(serverExecutor);
        httpServer.createContext("/themes", exchange -> respondSlowly(exchange,
                "{\"themes\":[{\"name\":\"twentytwentyfour\",\"status\":\"active\"}]}"));
        httpServer.createContext("/plugins", exchange -> respondSlowly(exchange,
                "{\"plugins\":[{\"name\":\"akismet\",\"status\":\"inactive\"}]}"));
        httpServer.start();
    }

    private static void respondSlowly(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        try {
            Thread.sleep(SLOW_RESPONSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }
}
