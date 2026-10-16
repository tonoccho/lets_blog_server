package com.letsblog.project.client;

import com.letsblog.project.cms.ConnectionCheckResult;
import com.letsblog.project.service.IdentityServiceUnavailableException;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectWorld;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部ブリッジのクライアントの、応答が空だったときなど、他のテストが通さない分岐(issue #1818)。
 *
 * <p>自前のクライアント(IdentityClient・IdentityBridgeClient・AiGenerationClient・CmsProvisioningBridgeClient)は
 * 実物で、HTTPの向こうだけをフェイクHTTPサーバーに差し替える。
 */
class ClientBranchTest {

    private static final String CMS = "/api/internal/project/cms/";

    private final ProjectWorld world = new ProjectWorld();

    @AfterEach
    void close() {
        world.close();
    }

    // ---- IdentityBridgeClient ----

    @Test
    void projectIdsForUser_本文が空なら空の一覧にする() {
        world.http.on("GET", "/api/internal/identity/users/5/project-ids", FakeHttpServer.Response.empty(200));

        assertEquals(List.of(), world.identityBridgeClient.projectIdsForUser(5L, "Bearer t"));
    }

    @Test
    void projectIdsForUser_identity_serviceが失敗すれば専用の例外にする() {
        world.fail("GET", "/api/internal/identity/users/5/project-ids", 500);

        assertThrows(IdentityServiceUnavailableException.class,
                () -> world.identityBridgeClient.projectIdsForUser(5L, "Bearer t"));
    }

    @Test
    void userGithubToken_本文が空なら未設定として扱う() {
        world.http.on("GET", "/api/internal/identity/users/5/github-token", FakeHttpServer.Response.empty(200));

        IdentityBridgeClient.UserGithubToken token = world.identityBridgeClient.userGithubToken(5L, "Bearer t");

        assertFalse(token.configured());
        assertNull(token.encryptedToken());
    }

    @Test
    void userGithubToken_identity_serviceが失敗すれば専用の例外にする() {
        world.fail("GET", "/api/internal/identity/users/5/github-token", 503);

        assertThrows(IdentityServiceUnavailableException.class,
                () -> world.identityBridgeClient.userGithubToken(5L, "Bearer t"));
    }

    @Test
    void reconcileRolesForSite_リクエストの外でBearerが渡されていればそれを使い_空ならサービス自身のトークンを使う() {
        String path = "/api/internal/identity/project-users/1/sites/20/reconcile-roles";
        world.http.on("POST", path, FakeHttpServer.Response.empty(200));

        world.identityBridgeClient.reconcileRolesForSite(1L, 20L, "Bearer given");
        world.identityBridgeClient.reconcileRolesForSite(1L, 20L, " ");
        world.identityBridgeClient.reconcileRolesForSite(1L, 20L, null);

        List<FakeHttpServer.Request> sent = world.http.requests("POST", path);
        assertEquals("Bearer given", sent.get(0).header("Authorization"));
        assertEquals("Bearer " + ProjectWorld.SERVICE_TOKEN, sent.get(1).header("Authorization"));
        assertEquals("Bearer " + ProjectWorld.SERVICE_TOKEN, sent.get(2).header("Authorization"));
    }

    @Test
    void reconcileRolesForSite_リクエスト中はBearerが空でもサービス自身のトークンへは切り替えない() {
        String path = "/api/internal/identity/project-users/1/sites/20/reconcile-roles";
        world.http.on("POST", path, FakeHttpServer.Response.empty(200));
        world.inRequest();

        world.identityBridgeClient.reconcileRolesForSite(1L, 20L, null);

        assertNull(world.http.requests("POST", path).get(0).header("Authorization"));
    }

    @Test
    void reconcileRolesForSite_失敗すれば専用の例外にする() {
        world.fail("POST", "/api/internal/identity/project-users/1/sites/20/reconcile-roles", 500);

        assertThrows(IdentityServiceUnavailableException.class,
                () -> world.identityBridgeClient.reconcileRolesForSite(1L, 20L, "Bearer t"));
    }

    // ---- IdentityClient ----

    @Test
    void fetchProfile_本文が空なら専用の例外にする() {
        world.http.on("GET", "/api/identity/me", FakeHttpServer.Response.empty(200));

        IdentityServiceUnavailableException e = assertThrows(IdentityServiceUnavailableException.class,
                () -> world.identityClient.fetchProfile("Bearer t"));

        assertTrue(e.getMessage().contains("空の応答"));
    }

    @Test
    void lookupProfile_401と403は操作者なし_それ以外の失敗は例外のまま伝える() {
        world.fail("GET", "/api/identity/me", 401);
        assertEquals(Optional.empty(), world.identityClient.lookupProfile("Bearer t"));

        world.fail("GET", "/api/identity/me", 403);
        assertEquals(Optional.empty(), world.identityClient.lookupProfile("Bearer t"));

        world.fail("GET", "/api/identity/me", 500);
        assertThrows(IdentityServiceUnavailableException.class, () -> world.identityClient.lookupProfile("Bearer t"));
    }

    @Test
    void lookupProfile_identity_serviceに届かなければ操作者なしにせず例外のまま伝える() {
        IdentityClient unreachable = new IdentityClient(RestClient.builder(), "http://127.0.0.1:1");

        assertThrows(IdentityServiceUnavailableException.class, () -> unreachable.lookupProfile("Bearer t"));
    }

    @Test
    void lookupProfile_本文が空なら原因の無い例外のまま伝える() {
        world.http.on("GET", "/api/identity/me", FakeHttpServer.Response.empty(200));

        assertThrows(IdentityServiceUnavailableException.class, () -> world.identityClient.lookupProfile("Bearer t"));
    }

    // ---- AiGenerationClient ----

    @Test
    void generate_本文が空なら専用の例外にする() {
        world.http.on("POST", "/api/internal/ai/generate", FakeHttpServer.Response.empty(200));

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.aiGenerationClient.generate(1L, "p", null));

        assertTrue(e.getMessage().contains("空の応答"));
    }

    // ---- CmsProvisioningBridgeClient ----

    @Test
    void testConnection_本文が空なら失敗として返す() {
        world.http.on("POST", CMS + "test-connection", FakeHttpServer.Response.empty(200));

        ConnectionCheckResult result = world.cmsBridgeClient.testConnection("WORDPRESS", Map.of("wpSlug", "s"));

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("空の応答"));
    }

    @Test
    void ブリッジ呼び出しはAuthorizationが空白なら何も付けない() {
        world.http.json("POST", CMS + "test-connection", world.toJson(new ConnectionCheckResult(true, null, null, null)));
        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.addHeader("Authorization", "  ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(blank));
        CmsProvisioningBridgeClient client = new CmsProvisioningBridgeClient(
                RestClient.builder(), world.http.baseUrl(), blank, world.serviceTokenClient);

        assertTrue(client.testConnection("WORDPRESS", Map.of("wpSlug", "s")).ok());

        assertNull(world.http.requests("POST", CMS + "test-connection").get(0).header("Authorization"));
    }
}
