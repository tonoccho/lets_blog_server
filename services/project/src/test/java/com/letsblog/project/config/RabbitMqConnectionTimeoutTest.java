package com.letsblog.project.config;

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
 * issue #1388: RabbitMQ停止中のプロジェクト作成が、gatewayのdefault-response-timeout(60秒、
 * {@code project}ルートに個別の上書きは無い。gatewayの application.yml 参照)より先に、
 * project-service自身が接続確立を諦めることを固定する。
 *
 * <p><b>実測で確定した原因</b>(issue #1388の見立て――接続タイムアウト未設定のまま
 * Spring AMQP既定の60秒待つ――は経路の説明として不正確だったため、実機で確かめ直した)。
 * 共有スタックで {@code docker compose stop rabbitmq} 直後に {@code POST /api/projects} を
 * 叩くと、単純なDNS未解決(高速失敗、1ms未満)ではなく、次の経路で丸ごと60秒止まった:
 *
 * <ol>
 *   <li>RabbitMQが起動中に成功した名前解決を、JVMが既定30秒キャッシュする
 *       ({@code java.security} の {@code networkaddress.cache.ttl} が未設定のときの既定値。
 *       project-serviceのコンテナで実際に確認: 該当行はコメントアウトされたまま)。</li>
 *   <li>RabbitMQを止めた直後、まだこのキャッシュが生きている間に発行を試みると、
 *       (もう存在しない)古いIPへTCP接続を試み、応答が来ないまま
 *       {@code CachingConnectionFactory} の接続確立ロックを握った状態で、RabbitMQ Java
 *       クライアント既定の接続タイムアウト(60000ms、{@code spring.rabbitmq.connection-timeout}
 *       未設定時の既定)いっぱいまで待つ。</li>
 *   <li>この間、同じ{@code ConnectionFactory}を使う他の全呼び出し
 *       (health indicatorの定期チェックを含む)もロック待ちで足止めされ、60秒後に
 *       一斉に失敗する(project-serviceのログで実測: 03:39:07.290 に接続試行が始まり、
 *       03:40:07.351 に監査ログ発行失敗としてようやく解放。ちょうど60.061秒)。</li>
 *   <li>gatewayの{@code project}ルートには個別の{@code response-timeout}が無いため、
 *       {@code default-response-timeout: 60s} が適用され、project-serviceが諦めるのとほぼ
 *       同時か、それより先にgatewayが接続を切って504を返す(実測: {@code curl}で60.013秒
 *       ちょうどで504)。</li>
 * </ol>
 *
 * <p>再現しない側の実測も取ってある: 直前の成功から30秒以上経ってから止めると、
 * 名前解決は失敗側(既定10秒キャッシュ)になっており、{@code UnknownHostException}で
 * 1ms未満で速く失敗する(504にならない)。issue #1388が「単独実行では再現しない」と
 * 記録していたのは、この30秒の運の問題だったためと分かる。
 *
 * <p>したがって対処は、{@code spring.rabbitmq.connection-timeout}をgatewayの応答時間より
 * 十分短く設定すること(このテストが選んだ手段。3秒 = project-serviceの他の内部クライアント
 * {@code IdentityClient}/{@code IdentityBridgeClient}/{@code CmsProvisioningBridgeClient}/
 * {@code AiGenerationClient}と同じ接続タイムアウトの慣例に合わせた)。
 * これにより「運悪く古いIPを引いた1回」が起きても、project-serviceが自分から
 * (60秒ではなく3秒で)諦め、{@code AuditLogService}/{@code DomainEventPublisher}の
 * 既存の{@code catch (AmqpException)}で監査ログ・ドメインイベントの発行失敗として
 * 記録するだけに留まり、業務操作自体はgatewayの応答時間内に成功する。
 * 監査ログが失われること自体はこのIssueの受け入れ範囲内
 * (同ファイルの別シナリオが明示的に検証している)。
 */
@DisplayName("project: RabbitMQ接続タイムアウトとgatewayの応答時間の整合(issue #1388)")
class RabbitMqConnectionTimeoutTest {

    @Test
    @DisplayName("spring.rabbitmq.connection-timeoutが設定され、projectルートのgateway応答時間より十分短い")
    void 接続タイムアウトがgatewayの応答時間より十分短い() throws IOException {
        RabbitProperties rabbitProperties = bindRabbitProperties();
        Duration connectionTimeout = rabbitProperties.getConnectionTimeout();
        assertNotNull(connectionTimeout,
                "spring.rabbitmq.connection-timeoutが設定されていません。既定のまま(RabbitMQ "
                        + "Javaクライアントの既定60秒)だと、RabbitMQ停止直後の古いDNSキャッシュを"
                        + "引いた接続試行がgatewayのdefault-response-timeout(60秒)と競合し、"
                        + "504になりうる(issue #1388、実測60.013秒)");

        Duration gatewayTimeout = projectRouteResponseTimeout();

        assertTrue(connectionTimeout.compareTo(gatewayTimeout) < 0,
                "spring.rabbitmq.connection-timeout(" + connectionTimeout + ")がgatewayの"
                        + "projectルートの応答時間(" + gatewayTimeout + ")以上です。RabbitMQ停止中に"
                        + "接続確立を試みると、project-serviceが諦めるより先にgatewayが切って"
                        + "504になる(issue #1388)");

        // 接続確立の失敗1回だけでgatewayの応答時間の大半を使い切らないよう、
        // 少なくとも半分以上の余裕を残す(健康チェック等の再試行が重なっても粘れるように)。
        assertTrue(connectionTimeout.multipliedBy(2).compareTo(gatewayTimeout) <= 0,
                "spring.rabbitmq.connection-timeout(" + connectionTimeout + ")がgatewayの"
                        + "応答時間(" + gatewayTimeout + ")の半分を超えています。接続確立の"
                        + "失敗が1回起きるだけでgatewayの応答時間を使い切りかねません");
    }

    private RabbitProperties bindRabbitProperties() throws IOException {
        Path ymlPath = repoRoot().resolve("services/project/src/main/resources/application.yml");
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
     * gatewayの{@code project}ルート(POST /api/projectsが実際にマッチする。より特定度の高い
     * {@code /api/projects/*}以下のサブパス専用ルートには当たらない)の実効応答時間。
     * 個別の{@code response-timeout}が無ければ{@code default-response-timeout}が適用される
     * (gatewayのapplication.yml参照)。
     */
    private Duration projectRouteResponseTimeout() throws IOException {
        Path ymlPath = repoRoot().resolve("services/gateway/src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(ymlPath), () -> "gatewayのapplication.ymlが見つかりません: " + ymlPath);

        Binder binder = yamlBinder(ymlPath);
        GatewayRouteProperties properties = binder.bind("app.gateway", GatewayRouteProperties.class)
                .orElseThrow(() -> new IllegalStateException("app.gatewayの束縛に失敗しました: " + ymlPath));

        GatewayRouteProperties.Route projectRoute = properties.getRoutes().stream()
                .filter(route -> "project".equals(route.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("gatewayにprojectルートがありません: " + ymlPath));

        return projectRoute.getResponseTimeout() != null
                ? projectRoute.getResponseTimeout()
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
     * {@code AiImageRouteTimeoutTest}(gateway側)と同じ。
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
