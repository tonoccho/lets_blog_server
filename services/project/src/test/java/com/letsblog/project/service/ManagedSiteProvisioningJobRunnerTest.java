package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.client.IdentityClient;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * サイト自動構築ジョブの実行本体(issue #1479)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する(ImageGenerationJobRunnerTestと同じ)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("project-service: サイト自動構築ジョブランナー(issue #1479)")
class ManagedSiteProvisioningJobRunnerTest {

    private static final CreateManagedWordPressSiteRequest REQUEST = new CreateManagedWordPressSiteRequest(
            "Name", "my-site", "Title", "admin", "admin@example.com", "pw", "ja", null);
    private static final ActorSnapshot ACTOR =
            new ActorSnapshot(5L, "sub-5", "a@example.com", "203.0.113.9", "ua", "Bearer abc", true);

    @Mock
    private WordPressSiteProvisioningService provisioningService;
    @Mock
    private GenerationJobClient generationJobClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    // 実物を使い、ジョブのスレッドでは操作者がスナップショットから引かれることを確かめる。
    private final CurrentActorService currentActorService =
            new CurrentActorService(new MockHttpServletRequest(), mock(IdentityClient.class));
    private ManagedSiteProvisioningJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ManagedSiteProvisioningJobRunner(
                provisioningService, currentActorService, generationJobClient, objectMapper);
    }

    private static SiteResponse site() {
        return new SiteResponse(7L, "Name", "my-site", null, "https://localhost/sites/my-site",
                Instant.now(), Instant.now(), "SUCCESS", true, false, null);
    }

    private JsonNode lastPayload(String status) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(11L), eq(status), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    @Test
    @DisplayName("成功すると provisioning → registering の進捗を running で通知し、done の結果にサイトIDを載せる")
    @SuppressWarnings("unchecked")
    void success() throws Exception {
        when(provisioningService.createManagedSiteForJob(eq(REQUEST), any())).thenAnswer(invocation -> {
            // ジョブのスレッドでも操作者(監査ログ・著者解決の参照先)が引ける。
            assertEquals(5L, currentActorService.getCurrentActorId());
            assertEquals("sub-5", currentActorService.getCurrentActorKeycloakSub());
            Consumer<String> listener = invocation.getArgument(1, Consumer.class);
            listener.accept("provisioning");
            listener.accept("registering");
            return site();
        });

        runner.run(11L, REQUEST, ACTOR);

        InOrder order = inOrder(generationJobClient);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        order.verify(generationJobClient, org.mockito.Mockito.times(2))
                .updateStatus(eq(11L), eq("running"), payload.capture());
        assertEquals("provisioning", objectMapper.readTree(payload.getAllValues().get(0)).get("phase").asText());
        assertEquals("registering", objectMapper.readTree(payload.getAllValues().get(1)).get("phase").asText());
        ArgumentCaptor<String> done = ArgumentCaptor.forClass(String.class);
        order.verify(generationJobClient).updateStatus(eq(11L), eq("done"), done.capture());
        JsonNode result = objectMapper.readTree(done.getValue());
        assertEquals("done", result.get("phase").asText());
        assertEquals(7L, result.get("siteId").asLong());
        assertEquals("my-site", result.get("siteKey").asText());
        assertFalse(done.getValue().contains("pw\""));
    }

    @Test
    @DisplayName("操作者の束縛は終わったら外れる")
    void actorUnboundAfterRun() {
        when(provisioningService.createManagedSiteForJob(any(), any())).thenReturn(site());

        runner.run(11L, REQUEST, ACTOR);

        org.junit.jupiter.api.Assertions.assertNull(currentActorService.getCurrentActorKeycloakSub());
    }

    @Test
    @DisplayName("siteKey 重複は duplicate_site_key の failed")
    void duplicate() throws Exception {
        when(provisioningService.createManagedSiteForJob(any(), any()))
                .thenThrow(new DuplicateSiteKeyException("siteKey 'my-site' は既に登録されています"));

        runner.run(11L, REQUEST, ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("duplicate_site_key", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("my-site"));
    }

    @Test
    @DisplayName("実体が既にある(409)は already_provisioned の failed")
    void alreadyProvisioned() throws Exception {
        when(provisioningService.createManagedSiteForJob(any(), any()))
                .thenThrow(new SiteAlreadyProvisionedException("既に構築済みです", null));

        runner.run(11L, REQUEST, ACTOR);

        assertEquals("already_provisioned", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("構築失敗は provisioning_failed の failed で、原因が読める")
    void provisioningFailed() throws Exception {
        when(provisioningService.createManagedSiteForJob(any(), any()))
                .thenThrow(new ProvisioningException("WordPress自動構築に失敗しました: wp-cli", null));

        runner.run(11L, REQUEST, ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("provisioning_failed", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("wp-cli"));
        verify(generationJobClient, never()).updateStatus(eq(11L), eq("done"), any());
    }

    @Test
    @DisplayName("テンプレート不正(IllegalArgumentException)・未登録は invalid_request の failed")
    void invalidRequest() throws Exception {
        when(provisioningService.createManagedSiteForJob(any(), any()))
                .thenThrow(new IllegalArgumentException("テンプレートには自動構築サイトのみ指定できます"));

        runner.run(11L, REQUEST, ACTOR);

        assertEquals("invalid_request", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("テンプレートサイトが無い(SiteNotFoundException)も invalid_request")
    void templateNotFound() throws Exception {
        when(provisioningService.createManagedSiteForJob(any(), any()))
                .thenThrow(new SiteNotFoundException("id 3 のテンプレートサイトは登録されていません"));

        runner.run(11L, REQUEST, ACTOR);

        assertEquals("invalid_request", lastPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("想定外の例外は unexpected_error の failed(メッセージが無くても読める文言になる)")
    void unexpected() throws Exception {
        when(provisioningService.createManagedSiteForJob(any(), any())).thenThrow(new IllegalStateException());

        runner.run(11L, REQUEST, ACTOR);

        JsonNode result = lastPayload("failed");
        assertEquals("unexpected_error", result.get("errorType").asText());
        assertFalse(result.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("ジョブのスレッドでは、取り置いた利用者のBearerをサービス間呼び出し用に束縛しない。操作者は引ける(#1723)")
    void userBearerNotBoundOnJobThread() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        when(provisioningService.createManagedSiteForJob(any(), any())).thenAnswer(invocation -> {
            seen.set(com.letsblog.project.client.BearerScope.current() + "/"
                    + currentActorService.getAuthorizationHeader() + "/" + currentActorService.getCurrentActorEmail());
            return site();
        });

        Thread t = new Thread(() -> runner.run(11L, REQUEST, ACTOR));
        t.start();
        t.join();

        assertEquals("null/null/a@example.com", seen.get());
        org.junit.jupiter.api.Assertions.assertNull(com.letsblog.project.client.BearerScope.current());
    }
}
