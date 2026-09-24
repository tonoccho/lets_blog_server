package com.letsblog.analytics.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1390: issue #1388がproject-serviceで確定させた「RabbitMQ停止直後、JVMの名前解決
 * キャッシュ(既定30秒)が生きている窓の中でだけ、古いIPへの接続試行がRabbitMQ Javaクライアント
 * 既定の接続タイムアウト(60秒)いっぱい{@code CachingConnectionFactory}の接続確立ロックを
 * 握ったまま止まり、gatewayの応答時間と競合して504になる」経路が、同じ{@code spring.rabbitmq}
 * ブロックを持つanalytics-serviceにも残っていないことを固定する。
 *
 * <p><b>コード根拠による判定: 直接のブロック経路は無い</b>。本サービスは{@code EventMessageListener}でletsblog.eventsを<b>消費するだけ</b>で
 * 、{@code RabbitTemplate.convertAndSend}をHTTPリクエストスレッドから呼ぶコード({@code AuditLogService}相当)は存在しない(grep済み)
 * 。したがって{@code CachingConnectionFactory}の接続確立ロックがHTTPリクエストを直接ブロックする経路は無い。ただし{@code spring.rabbitmq}ブロッ
 * クを持つ他8サービスと同じ構成であり、将来同期publish経路が足された際に同じ欠陥を持ち込まないよう、足並みを揃えて設定する(issue #1390の方針: 一度に9サービスへ入れる)。
 *
 * <p>対処は project-service と同じ: {@code spring.rabbitmq.connection-timeout} をgatewayの
 * 応答時間より十分短く設定する。値は本サービスの他の内部クライアント({@code IdentityBridgeClient}/{@code ProjectBridgeClient}、いずれも{@cod
 * e CONNECT_TIMEOUT = Duration.ofSeconds(3)})と同じ3秒の慣例に合わせた。
 */
@DisplayName("analytics: RabbitMQ接続タイムアウトとgatewayの応答時間の整合(issue #1390)")
class RabbitMqConnectionTimeoutTest {

    @Test
    @DisplayName("spring.rabbitmq.connection-timeoutが設定され、project-dashboard-analyticsルートのgateway応答時間より十分短い")
    void 接続タイムアウトがgatewayの応答時間より十分短い() throws IOException {
        RabbitProperties rabbitProperties = bindRabbitProperties();
        Duration connectionTimeout = rabbitProperties.getConnectionTimeout();
        assertNotNull(connectionTimeout,
                "spring.rabbitmq.connection-timeoutが設定されていません。既定のまま(RabbitMQ "
                        + "Javaクライアントの既定60秒)だと、RabbitMQ停止直後の古いDNSキャッシュを"
                        + "引いた接続試行がgatewayの応答時間と競合し、504になりうる(issue #1388/#1390)");

        Duration gatewayTimeout = routeResponseTimeout();

        assertTrue(connectionTimeout.compareTo(gatewayTimeout) < 0,
                "spring.rabbitmq.connection-timeout(" + connectionTimeout + ")がgatewayの"
                        + "project-dashboard-analyticsルートの応答時間(" + gatewayTimeout + ")以上です。RabbitMQ停止中に"
                        + "接続確立を試みると、本サービスが諦めるより先にgatewayが切って504になる(issue #1388/#1390)");

        // 接続確立の失敗1回だけでgatewayの応答時間の大半を使い切らないよう、
        // 少なくとも半分以上の余裕を残す(健康チェック等の再試行が重なっても粘れるように)。
        assertTrue(connectionTimeout.multipliedBy(2).compareTo(gatewayTimeout) <= 0,
                "spring.rabbitmq.connection-timeout(" + connectionTimeout + ")がgatewayの"
                        + "応答時間(" + gatewayTimeout + ")の半分を超えています。接続確立の"
                        + "失敗が1回起きるだけでgatewayの応答時間を使い切りかねません");
    }

    private RabbitProperties bindRabbitProperties() throws IOException {
        Path ymlPath = repoRoot().resolve("services/analytics/src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(ymlPath), () -> "application.ymlが見つかりません: " + ymlPath);

        Binder binder = yamlBinder(ymlPath);
        return binder.bind("spring.rabbitmq", RabbitProperties.class)
                .orElseThrow(() -> new IllegalStateException("spring.rabbitmqの束縛に失敗しました: " + ymlPath));
    }

    /**
     * YAMLを読み込み、{@code ${RABBITMQ_PORT:5672}}のようなプレースホルダを既定値へ
     * 解決したうえで束縛できる{@link Binder}を作る。プレースホルダを解決しないままだと、
     * 整数フィールド({@code port}等)への変換で{@link NumberFormatException}になる。
     */
    private Binder yamlBinder(Path ymlPath) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load("application", new FileSystemResource(ymlPath));
        MutablePropertySources mutablePropertySources = new MutablePropertySources();
        propertySources.forEach(mutablePropertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(mutablePropertySources));
    }

    /**
     * gatewayの{@code project-dashboard-analytics}ルート({@code /api/projects/*&#47;dashboard/**}が
     * マッチする。個別のresponse-timeout上書きは無い)の実効応答時間。
     * 個別の{@code response-timeout}が無ければ{@code default-response-timeout}が適用される
     * (gatewayのapplication.yml参照)。
     */
    private Duration routeResponseTimeout() throws IOException {
        Path ymlPath = repoRoot().resolve("services/gateway/src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(ymlPath), () -> "gatewayのapplication.ymlが見つかりません: " + ymlPath);

        Binder binder = yamlBinder(ymlPath);
        GatewayRouteProperties properties = binder.bind("app.gateway", GatewayRouteProperties.class)
                .orElseThrow(() -> new IllegalStateException("app.gatewayの束縛に失敗しました: " + ymlPath));

        GatewayRouteProperties.Route route = properties.getRoutes().stream()
                .filter(r -> "project-dashboard-analytics".equals(r.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "gatewayにproject-dashboard-analyticsルートがありません: " + ymlPath));

        return route.getResponseTimeout() != null
                ? route.getResponseTimeout()
                : properties.getDefaultResponseTimeout();
    }

    private Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("settings.gradleが見つからずリポジトリルートを特定できませんでした");
    }

    /**
     * gatewayの{@code RouteProperties}(services/gateway、モジュール外なので直接importしない)と
     * 同じ形の最小限の写し。gateway側のapplication.ymlを直接読んで束縛する方針は
     * project-serviceの{@code RabbitMqConnectionTimeoutTest}(issue #1388)と同じ。
     */
    static class GatewayRouteProperties {
        private Duration defaultResponseTimeout = Duration.ofSeconds(60);
        private List<Route> routes = new ArrayList<>();

        public Duration getDefaultResponseTimeout() {
            return defaultResponseTimeout;
        }

        public void setDefaultResponseTimeout(Duration defaultResponseTimeout) {
            this.defaultResponseTimeout = defaultResponseTimeout;
        }

        public List<Route> getRoutes() {
            return routes;
        }

        public void setRoutes(List<Route> routes) {
            this.routes = routes;
        }

        static class Route {
            private String id;
            private Duration responseTimeout;

            public String getId() {
                return id;
            }

            public void setId(String id) {
                this.id = id;
            }

            public Duration getResponseTimeout() {
                return responseTimeout;
            }

            public void setResponseTimeout(Duration responseTimeout) {
                this.responseTimeout = responseTimeout;
            }
        }
    }
}
