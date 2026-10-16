package com.letsblog.platform.testsupport;

import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.service.AppSettingService;
import com.letsblog.platform.service.GitLabReachabilityChecker;
import com.letsblog.platform.service.LetsBlogServiceStatusService;
import com.letsblog.platform.service.PenpotCheckerFactory;
import com.letsblog.platform.service.PenpotReachabilityChecker;
import com.letsblog.platform.service.RabbitMqQueueStatusService;
import com.letsblog.platform.service.SystemSettingService;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.client.RestClient;

/**
 * 連携状況・コンテナ状況のテストが使う「自前のクラスはすべて実物の世界」(issue #1825)。
 *
 * <p>{@link PlatformWorld}の上に、設定サービス({@link AppSettingService}・{@link SystemSettingService})、
 * gatewayの集約ヘルス({@link LetsBlogServiceStatusService})、RabbitMQ管理API({@link RabbitMqQueueStatusService})、
 * Penpot・GitLabの到達性チェッカーを実物で組み立てる。差し替える外部との通信は次だけ。
 * <ul>
 *   <li>HTTP: {@link FakeHttpServer}。gatewayは{@code /gw}、RabbitMQ管理APIは{@code /rmq}、Penpotは{@code /penpot}、
 *       GitLabは{@code /gitlab}の下に置く。</li>
 *   <li>DB: {@code system_settings}のSpring Dataリポジトリ(メモリ上の実装)。</li>
 * </ul>
 */
public final class StatusFixture implements AutoCloseable {

    public static final List<String> GATEWAY_COMPONENTS = List.of(
            "identityService", "projectService", "contentService", "mediaService", "aiService",
            "analyticsService", "publishingService", "platformService", "logWriterService");

    public final PlatformWorld world = new PlatformWorld().actingAsAdmin(1L);
    public final FakeHttpServer http = world.http;
    public final Map<String, SystemSetting> store = new ConcurrentHashMap<>();
    public final AppSettingService appSettingService;
    public final SystemSettingService systemSettingService;
    public final LetsBlogServiceStatusService letsBlogServiceStatusService;
    public final RabbitMqQueueStatusService rabbitMqQueueStatusService;
    public final PenpotReachabilityChecker penpotChecker;
    public final GitLabReachabilityChecker gitLabChecker;

    public StatusFixture() {
        appSettingService = new AppSettingService(
                InMemoryRepositories.systemSettings(store), world.credentialCipher, world.adminAuthorizationService,
                "gpt-4o-mini", "gpt-4o-mini,gpt-4o", "120",
                "OPENAI", "claude-3-5-haiku-20241022", "qwen2.5:7b-instruct",
                "smtp.example.com", "587", "env-user", "env-pass", "noreply@example.com",
                "http://localhost:3000", "10", "wp-admin");
        systemSettingService = new SystemSettingService(
                InMemoryRepositories.systemSettings(store), world.credentialCipher, world.adminAuthorizationService, "");
        letsBlogServiceStatusService = new LetsBlogServiceStatusService(http.baseUrl() + "/gw");
        rabbitMqQueueStatusService = new RabbitMqQueueStatusService(http.baseUrl() + "/rmq", "guest", "guest");
        penpotChecker = penpotChecker(http.baseUrl() + "/penpot");
        gitLabChecker = new GitLabReachabilityChecker();
        gatewayAllUp();
        rabbitQueues("[]");
        http.on("GET", "/penpot", FakeHttpServer.Response.empty(200));
        http.on("GET", "/gitlab/users/sign_in", FakeHttpServer.Response.empty(200));
    }

    /** {@code penpot.lan}だけが検査を通る(LANの)アドレスへ解決するガードを持つ実物のチェッカー。HTTPだけをフェイクサーバーへ向ける。 */
    public static PenpotReachabilityChecker penpotChecker(String fakeBaseUrl) {
        return PenpotCheckerFactory.create(
                new ConnectionDestinationGuard(
                        h -> new InetAddress[] {InetAddress.getByName(h.equals("penpot.lan") ? "192.168.1.50" : h)},
                        List::of, () -> false),
                target -> RestClient.builder().baseUrl(fakeBaseUrl).build());
    }

    public void setting(String key, String value) {
        store.put(key, new SystemSetting(key, world.credentialCipher.encrypt(value)));
    }

    public void llmProvider(String provider) {
        setting("llm_provider", provider);
    }

    public void braveKey(boolean configured) {
        if (configured) {
            setting(SystemSettingService.BRAVE_SEARCH_API_KEY, "brave-key");
        } else {
            store.remove(SystemSettingService.BRAVE_SEARCH_API_KEY);
        }
    }

    public String gitlabUrl() {
        return http.baseUrl() + "/gitlab";
    }

    /** gatewayの集約ヘルス。{@code downs}に挙げたコンポーネントだけDOWN、あとは全部UP。下流がDOWNならActuatorは503を返す。 */
    public void gatewayDown(String... downs) {
        List<String> downList = List.of(downs);
        StringBuilder json = new StringBuilder("{\"status\":\"").append(downList.isEmpty() ? "UP" : "DOWN")
                .append("\",\"components\":{");
        for (int i = 0; i < GATEWAY_COMPONENTS.size(); i++) {
            String name = GATEWAY_COMPONENTS.get(i);
            json.append(i == 0 ? "" : ",").append('"').append(name).append("\":{\"status\":\"")
                    .append(downList.contains(name) ? "DOWN" : "UP").append("\"}");
        }
        json.append("}}");
        http.on("GET", "/gw/actuator/health",
                FakeHttpServer.Response.json(downList.isEmpty() ? 200 : 503, json.toString()));
    }

    public void gatewayAllUp() {
        gatewayDown();
    }

    public void rabbitQueues(String json) {
        http.on("GET", "/rmq/api/queues", FakeHttpServer.Response.ok(json));
    }

    @Override
    public void close() {
        world.close();
    }
}
