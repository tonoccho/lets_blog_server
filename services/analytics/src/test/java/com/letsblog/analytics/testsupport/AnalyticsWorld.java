package com.letsblog.analytics.testsupport;

import com.letsblog.analytics.adsense.AdSenseClient;
import com.letsblog.analytics.analytics.GoogleAnalyticsClient;
import com.letsblog.analytics.client.IdentityBridgeClient;
import com.letsblog.analytics.client.ProjectBridgeClient;
import com.letsblog.analytics.config.GlobalExceptionHandler;
import com.letsblog.analytics.controller.InternalAnalyticsProjectSettingsController;
import com.letsblog.analytics.controller.ProjectAnalyticsApiKeyController;
import com.letsblog.analytics.controller.ProjectDashboardController;
import com.letsblog.analytics.domain.AnalyticsCredentials;
import com.letsblog.analytics.repository.AnalyticsCredentialsRepository;
import com.letsblog.analytics.service.AdSenseReportService;
import com.letsblog.analytics.service.AdminAuthorizationService;
import com.letsblog.analytics.service.AnalyticsCredentialsService;
import com.letsblog.analytics.service.CurrentActorService;
import com.letsblog.analytics.service.GoogleAnalyticsReportService;
import com.letsblog.analytics.service.ProjectAnalyticsSettingsService;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.crypto.CredentialCipher;
import jakarta.servlet.http.HttpServletRequest;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * services/analytics のテストの土台(issue #1827)。自前のクラス(サービス・コントローラ・{@code *Client})は
 * <b>すべて実物</b>で組み立て、外部との通信だけを差し替える。
 *
 * <ul>
 *   <li><b>HTTP</b>: {@link FakeHttpServer}(サーバー側フェイク)1台。identity-service の {@code /api/identity/me}・
 *       プロジェクトメンバー判定、project-service のブリッジ、Google の OAuth トークン / GA4 Admin・Data API /
 *       AdSense Management API の応答を、ベース URL だけ変えた実物のクライアントに返す。</li>
 *   <li><b>DB</b>: {@link AnalyticsCredentialsRepository}(Spring Data のインタフェース。自前のコードを持たない)
 *       だけを Mockito の {@code mock()} にし、{@code findByProjectId} / {@code save} / {@code deleteByProjectId} を
 *       メモリ上の {@code Map} で応答させる。{@link AnalyticsCredentialsService} 以降は実物。</li>
 *   <li><b>リクエスト</b>: {@code CurrentActorService} が受け取る {@code HttpServletRequest} は、Spring が
 *       request スコープの Bean に差し込むプロキシと同じく、{@link RequestContextHolder} の現在のリクエストへ委譲する
 *       (Mockito ではなく {@link Proxy})。{@code MockMvc} は実行中にそのホルダーを設定し、サービス直接呼び出しの
 *       テストは {@link #asBearer(String)} で設定する。</li>
 * </ul>
 */
public final class AnalyticsWorld implements AutoCloseable {

    public final FakeHttpServer http = new FakeHttpServer();

    /** 資格情報の「テーブル」。キーは projectId。 */
    public final Map<Long, AnalyticsCredentials> store = new ConcurrentHashMap<>();
    public final AnalyticsCredentialsRepository repository;
    public final CredentialCipher cipher = new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));

    public final AnalyticsCredentialsService credentials;
    public final IdentityClient identityClient;
    public final IdentityBridgeClient identityBridgeClient;
    public final ProjectBridgeClient projectBridgeClient;
    public final GoogleAnalyticsClient googleAnalyticsClient;
    public final AdSenseClient adSenseClient;
    public final CurrentActorService currentActor;
    public final AdminAuthorizationService authorization;
    public final ProjectAnalyticsSettingsService settings;
    public final GoogleAnalyticsReportService gaReports;
    public final AdSenseReportService adSenseReports;
    public final MockMvc mvc;

    private final FakeBackends backends = new FakeBackends(http);
    private final AtomicLong ids = new AtomicLong(1);

    public AnalyticsWorld() {
        repository = inMemoryRepository();

        String base = http.baseUrl();
        credentials = new AnalyticsCredentialsService(repository);
        identityClient = new IdentityClient(RestClient.builder(), base);
        identityBridgeClient = new IdentityBridgeClient(RestClient.builder(), base);
        projectBridgeClient = new ProjectBridgeClient(RestClient.builder(), base);
        googleAnalyticsClient = new GoogleAnalyticsClient(base + "/ga-data", base + "/ga-admin", base + "/oauth/token");
        adSenseClient = new AdSenseClient(base + "/oauth/token", base + "/adsense");
        currentActor = new CurrentActorService(currentRequestProxy(), identityClient);
        authorization = new AdminAuthorizationService(currentActor, identityBridgeClient);
        settings = new ProjectAnalyticsSettingsService(credentials, adSenseClient, googleAnalyticsClient, cipher);
        gaReports = new GoogleAnalyticsReportService(
                projectBridgeClient, credentials, googleAnalyticsClient, authorization, currentActor, cipher);
        adSenseReports = new AdSenseReportService(
                projectBridgeClient, credentials, adSenseClient, authorization, currentActor, cipher);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ProjectDashboardController(gaReports, adSenseReports),
                        new InternalAnalyticsProjectSettingsController(settings, authorization),
                        new ProjectAnalyticsApiKeyController(settings, authorization, projectBridgeClient, currentActor))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ---- 外部通信(HTTP)の状態 ----

    /** identity-service が、このBearerを「userId=id・role」の操作者として解決する。 */
    public AnalyticsWorld actor(String bearerToken, long id, String role) {
        backends.actor(bearerToken, id, role);
        return this;
    }

    /** identity-service が、userId をプロジェクトのメンバーとして答える。 */
    public AnalyticsWorld member(long projectId, long userId) {
        backends.member(projectId, userId);
        return this;
    }

    /** project-service が、プロジェクトの本番サイトの有無を答える。 */
    public AnalyticsWorld project(long projectId, boolean hasProductionSite) {
        backends.project(projectId, hasProductionSite);
        return this;
    }

    // ---- 外部通信(DB)の状態 ----

    /** GA の資格情報(連携済み・プロパティ選択済み)を保存済みにする。null の項目は保存しない。 */
    public AnalyticsCredentials seedGa(
            long projectId, String propertyId, String clientId, String clientSecret, String refreshToken) {
        AnalyticsCredentials c = row(projectId);
        c.setGaPropertyId(propertyId);
        c.setGaOauthClientId(clientId);
        c.setGaOauthClientSecretEncrypted(clientSecret == null ? null : cipher.encrypt(clientSecret));
        c.setGaRefreshTokenEncrypted(refreshToken == null ? null : cipher.encrypt(refreshToken));
        return c;
    }

    /** AdSense の資格情報を保存済みにする。null の項目は保存しない。 */
    public AnalyticsCredentials seedAdSense(
            long projectId, String accountId, String clientId, String clientSecret, String refreshToken) {
        AnalyticsCredentials c = row(projectId);
        c.setAdsenseAccountId(accountId);
        c.setAdsenseOauthClientId(clientId);
        c.setAdsenseOauthClientSecretEncrypted(clientSecret == null ? null : cipher.encrypt(clientSecret));
        c.setAdsenseRefreshTokenEncrypted(refreshToken == null ? null : cipher.encrypt(refreshToken));
        return c;
    }

    private AnalyticsCredentials row(long projectId) {
        return store.computeIfAbsent(projectId, id -> {
            AnalyticsCredentials c = new AnalyticsCredentials(id);
            c.setId(ids.getAndIncrement());
            return c;
        });
    }

    private AnalyticsCredentialsRepository inMemoryRepository() {
        AnalyticsCredentialsRepository mock = Mockito.mock(AnalyticsCredentialsRepository.class);
        when(mock.findByProjectId(any())).thenAnswer(inv -> Optional.ofNullable(store.get((Long) inv.getArgument(0))));
        when(mock.save(any(AnalyticsCredentials.class))).thenAnswer(inv -> {
            AnalyticsCredentials saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(ids.getAndIncrement());
            }
            store.put(saved.getProjectId(), saved);
            return saved;
        });
        doAnswer(inv -> {
            store.remove((Long) inv.getArgument(0));
            return null;
        }).when(mock).deleteByProjectId(any());
        return mock;
    }

    // ---- リクエスト ----

    /** サービスを直接呼ぶテスト用: 現在のリクエストに {@code Authorization: Bearer <token>} を付ける。null ならヘッダー無し。 */
    public MockHttpServletRequest asBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    /** サービスを直接呼ぶテスト用: 現在のリクエストに、そのままの値の {@code Authorization} ヘッダーを付ける。 */
    public MockHttpServletRequest withAuthorizationHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private static HttpServletRequest currentRequestProxy() {
        return (HttpServletRequest) Proxy.newProxyInstance(
                AnalyticsWorld.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> {
                    RequestAttributes attributes = RequestContextHolder.currentRequestAttributes();
                    HttpServletRequest current = ((ServletRequestAttributes) attributes).getRequest();
                    try {
                        return method.invoke(current, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Override
    public void close() {
        RequestContextHolder.resetRequestAttributes();
        http.close();
    }
}
