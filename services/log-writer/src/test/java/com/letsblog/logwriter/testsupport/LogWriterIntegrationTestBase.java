package com.letsblog.logwriter.testsupport;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.domain.FrontendErrorLog;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.repository.AuditLogRepository;
import com.letsblog.logwriter.repository.FrontendErrorLogRepository;
import com.letsblog.logwriter.repository.OperationLogRepository;
import java.time.LocalDateTime;
import com.letsblog.logwriter.testsupport.FakeHttpServer.Response;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.mockito.Mockito.when;

/**
 * log-writer のテストの共通の親(issue #1829)。自前のクラス(サービス・コントローラ・リポジトリを使う側・
 * {@code IdentityClient}・{@code GenerationJobClient})は<b>すべて実物の Bean</b>のまま、外部との通信だけを差し替える。
 *
 * <ul>
 *   <li><b>DB</b>: ADR-0006 の実 MySQL({@code lbs_log_test})。Spring Data のリポジトリも実物。各テストの前後で
 *       3 テーブルを空にする(テスト専用スキーマで、テストは直列に走る)。</li>
 *   <li><b>HTTP</b>: {@link FakeHttpServer}(サーバー側フェイク)1 台。identity-service の {@code GET /api/identity/me}
 *       と ai-service の {@code GET /api/generation-jobs} の応答を載せ、ベース URL だけをそちらへ向ける。</li>
 *   <li><b>MQ</b>: {@link RabbitTemplate} を {@code @MockitoBean} にする(ブローカーへの発行そのもの)。
 *       発行の失敗は {@code doThrow(AmqpException)} で作る。</li>
 *   <li><b>Keycloak の鍵取得</b>: {@link JwtDecoder} を {@code @MockitoBean} にする(JWKS への HTTP を伴う)。</li>
 * </ul>
 *
 * <p>{@code @DynamicPropertySource} やモックの組はテストクラスごとに変えると Spring が別のコンテキストとみなして
 * DB の接続プールをクラスごとに作ってしまうので、この親に 1 組だけ置く。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class LogWriterIntegrationTestBase {

    protected static final FakeHttpServer HTTP = new FakeHttpServer();
    private static final Map<String, String> ACTORS = new ConcurrentHashMap<>();
    private static final int DEFAULT_ROUTES;

    static {
        // identity-service: 操作者の解決。未登録のトークンは 401(操作者なし)。
        HTTP.on("GET", "/api/identity/me", request -> {
            String profile = ACTORS.get(request.header("Authorization"));
            return profile == null ? Response.json(401, "{}") : Response.ok(profile);
        });
        DEFAULT_ROUTES = HTTP.routeCount();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected OperationLogRepository operationLogRepository;

    @Autowired
    protected AuditLogRepository auditLogRepository;

    @Autowired
    protected FrontendErrorLogRepository frontendErrorLogRepository;

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @MockitoBean
    protected RabbitTemplate rabbitTemplate;

    @DynamicPropertySource
    static void externalHttp(DynamicPropertyRegistry registry) {
        registry.add("app.identity-service-uri", HTTP::baseUrl);
        registry.add("app.ai-service-uri", HTTP::baseUrl);
    }

    @BeforeEach
    void resetWorld() {
        HTTP.clearRoutesAfter(DEFAULT_ROUTES);
        ACTORS.clear();
        wipeLogTables();
    }

    @AfterEach
    void clearRequestState() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        wipeLogTables();
    }

    private void wipeLogTables() {
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM operation_logs");
        jdbc.update("DELETE FROM frontend_error_logs");
    }

    // ---- 外部通信(HTTP / JWT 検証)の状態 ----

    /** identity-service が、{@code Bearer <token>} を「userId=id・role」の操作者として解決する。 */
    protected void actor(String bearerToken, long id, String role) {
        ACTORS.put("Bearer " + bearerToken, "{\"id\":" + id + ",\"role\":\"" + role + "\"}");
    }

    /**
     * MockMvc 越しに使えるログイン状態を作る: JwtDecoder が {@code token} を {@code sub} の JWT として復号し、
     * identity-service が同じトークンを userId=id・role の操作者として解決する。
     */
    protected void login(String token, String sub, long id, String role) {
        when(jwtDecoder.decode(token)).thenReturn(JwtTestFixtures.jwt(sub, role));
        actor(token, id, role);
    }

    // ---- サービスを直接呼ぶテスト用のリクエスト状態 ----

    /**
     * 現在のリクエストに {@code Authorization: Bearer <token>} を付ける(null ならヘッダー無し)。
     * Spring が request スコープの {@code HttpServletRequest} に差し込むプロキシは、この現在のリクエストへ委譲する。
     */
    protected MockHttpServletRequest asBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    /** 現在のリクエストに、そのままの値の {@code Authorization} ヘッダーを付ける。 */
    protected MockHttpServletRequest withAuthorizationHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    /** SecurityContext に、{@code sub} を持つ JWT 認証を置く({@code getCurrentActorKeycloakSub} 用)。 */
    protected void authenticateAs(String sub) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(JwtTestFixtures.jwt(sub)));
    }

    // ---- 外部通信(DB)の状態: 実 MySQL に行を入れる ----

    protected OperationLog saveOperation(
            Long userId, String operationId, String method, String path, long durationMs, LocalDateTime createdAt) {
        OperationLog row = new OperationLog();
        row.setUserId(userId);
        row.setActorKeycloakSub(userId == null ? null : "sub-" + userId);
        row.setOperationId(operationId);
        row.setMethod(method);
        row.setPath(path);
        row.setStatusCode(200);
        row.setDurationMs(durationMs);
        row.setSuccess(true);
        row.setCreatedAt(createdAt);
        return operationLogRepository.save(row);
    }

    protected AuditLog saveAudit(
            Long userId, String action, String resourceType, Long resourceId, String remoteIp, LocalDateTime createdAt) {
        AuditLog row = new AuditLog();
        row.setUserId(userId);
        row.setActorKeycloakSub("audit-sub-" + userId);
        row.setAction(action);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        row.setRemoteIp(remoteIp);
        row.setCreatedAt(createdAt);
        return auditLogRepository.save(row);
    }

    protected FrontendErrorLog saveError(String message, String level, String url, LocalDateTime createdAt) {
        FrontendErrorLog row = new FrontendErrorLog();
        row.setMessage(message);
        row.setLevel(level);
        row.setUrl(url);
        row.setTimestamp(createdAt);
        row.setCreatedAt(createdAt);
        return frontendErrorLogRepository.save(row);
    }
}
