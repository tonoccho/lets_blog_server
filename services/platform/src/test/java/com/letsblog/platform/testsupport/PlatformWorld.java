package com.letsblog.platform.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.client.IdentityBridgeClient;
import com.letsblog.platform.service.AdminAuthorizationService;
import com.letsblog.platform.service.AuditLogService;
import com.letsblog.platform.service.CurrentActorService;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * platform-serviceのテストが使う「外部との通信の境界だけを差し替えた世界」(issue #1825)。
 *
 * <p>自前の{@code CurrentActorService}・{@code IdentityBridgeClient}・{@code AdminAuthorizationService}・
 * {@code AuditLogService}はすべて実物で、差し替えるのは外部との通信だけ。
 * <ul>
 *   <li><b>HTTP</b>(identity-service): {@link FakeHttpServer}(JDK標準のHttpServerをループバックで起動した
 *       サーバー側フェイク)。{@code IdentityClient}と{@code IdentityBridgeClient}の実物がこれを呼ぶ。</li>
 *   <li><b>MQ</b>: {@link RabbitTemplate}のMockito mock(実物の{@link AuditLogService}へ渡す)。</li>
 * </ul>
 */
public final class PlatformWorld implements AutoCloseable {

    public final FakeHttpServer http = new FakeHttpServer();
    public final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    public final MockHttpServletRequest request = new MockHttpServletRequest();
    public final CredentialCipher credentialCipher =
            new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    /** MQ(RabbitTemplate)。外部との通信なので差し替えてよい。 */
    public final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);

    public final IdentityClient identityClient;
    public final CurrentActorService currentActorService;
    public final IdentityBridgeClient identityBridgeClient;
    public final AdminAuthorizationService adminAuthorizationService;
    public final AuditLogService auditLogService = new AuditLogService(rabbitTemplate);

    private final Map<String, ActorProfile> profilesByAuthorization = new HashMap<>();
    private final Map<String, Boolean> membership = new HashMap<>();

    public PlatformWorld() {
        http.on("GET", "/api/identity/me", req -> {
            ActorProfile profile = profilesByAuthorization.get(req.header("Authorization"));
            return profile == null
                    ? FakeHttpServer.Response.json(401, "{\"error\":\"unauthorized\"}")
                    : FakeHttpServer.Response.ok(toJson(profile));
        });
        http.on("GET", "/api/internal/identity/projects/\\d+/members/\\d+", req -> {
            String[] parts = req.path().split("/");
            String key = parts[5] + ":" + parts[7];
            return FakeHttpServer.Response.ok(String.valueOf(membership.getOrDefault(key, false)));
        });
        identityClient = new IdentityClient(RestClient.builder(), http.baseUrl());
        currentActorService = new CurrentActorService(request, identityClient);
        identityBridgeClient = new IdentityBridgeClient(RestClient.builder(), http.baseUrl());
        adminAuthorizationService = new AdminAuthorizationService(currentActorService, identityBridgeClient);
    }

    /** リクエストの中にいる状態にする({@code RequestContextHolder}へリクエストを束縛する)。 */
    public PlatformWorld inRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return this;
    }

    /** 指定の利用者としてリクエストする。identity-serviceの{@code /api/identity/me}がそのヘッダーに返すプロフィールを登録する。 */
    public PlatformWorld actingAs(Long userId, String role) {
        String authorization = "Bearer token-" + userId;
        request.removeHeader("Authorization");
        request.addHeader("Authorization", authorization);
        request.removeAttribute(CurrentActorService.class.getName() + ".profile");
        profilesByAuthorization.put(authorization, new ActorProfile(userId, role));
        return inRequest();
    }

    public PlatformWorld actingAsAdmin(Long userId) {
        return actingAs(userId, "admin");
    }

    public PlatformWorld actingAsUser(Long userId) {
        return actingAs(userId, "user");
    }

    /** Authorizationヘッダーを持たない(未認証の)リクエストにする。 */
    public PlatformWorld anonymous() {
        request.removeHeader("Authorization");
        request.removeAttribute(CurrentActorService.class.getName() + ".profile");
        return inRequest();
    }

    /** 利用者{@code userId}をプロジェクト{@code projectId}のメンバーとして、identity-serviceが答える。 */
    public PlatformWorld projectMember(long projectId, long userId, boolean member) {
        membership.put(projectId + ":" + userId, member);
        return this;
    }

    /** JWTのsubを{@code subject}にする(監査用の{@code getCurrentActorKeycloakSub}が返す値)。nullなら未ログイン。 */
    public PlatformWorld signedInAs(String subject) {
        SecurityContextHolder.clearContext();
        if (subject != null) {
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        }
        return this;
    }

    /** identity-serviceが{@code /api/identity/me}で障害(503)を返す状態にする。 */
    public PlatformWorld identityServiceDown() {
        http.status("GET", "/api/identity/me", 503, "{\"error\":\"down\"}");
        return this;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** テストの終わりに、スレッドに束縛したリクエストとセキュリティコンテキストを外す。 */
    public void reset() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Override
    public void close() {
        reset();
        http.close();
    }
}
