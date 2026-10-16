package com.letsblog.ai.controller;

import com.letsblog.ai.dto.ProjectApiKeyStatusResponse;
import com.letsblog.ai.dto.SetProjectOpenAiApiKeyRequest;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.testsupport.AiWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProjectOpenAiApiKeyController(issue #1506)。認可は requireProjectMemberOrAdmin、キーは暗号化して保存する。
 *
 * <p>issue #1824: ProjectAiSettingsService・CredentialCipher・AdminAuthorizationServiceは実物で、
 * identity-serviceはHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class ProjectOpenAiApiKeyControllerTest {

    private AiWorld world;
    private ProjectOpenAiApiKeyController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        controller = new ProjectOpenAiApiKeyController(
                world.projectAiSettingsService, world.credentialCipher, world.adminAuthorizationService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void member() {
        world.member(10L);
        world.http.json("GET", "/api/internal/identity/projects/7/members/10", "true");
    }

    @Test
    void status_メンバー判定後に設定有無だけを返す() {
        member();

        assertFalse(controller.status(7L).configured());
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-abc"));
        ProjectApiKeyStatusResponse second = controller.status(7L);
        assertTrue(second.configured());

        assertEquals(2, world.http.requests("GET", "/api/internal/identity/projects/7/members/10").size());
    }

    @Test
    void set_暗号化して保存し204を返す() {
        member();

        assertEquals(204, controller.set(7L, new SetProjectOpenAiApiKeyRequest("sk-abc")).getStatusCode().value());

        byte[] stored = world.projectAiSettingsService.getOpenAiApiKeyEncrypted(7L);
        // 平文では保存されず、実物の暗号器で復号すると元のキーに戻る。
        assertFalse(new String(stored, java.nio.charset.StandardCharsets.UTF_8).contains("sk-abc"));
        assertEquals("sk-abc", world.credentialCipher.decrypt(stored));
        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/7/members/10").size());
    }

    @Test
    void clear_nullを保存して削除し204を返す() {
        member();
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-abc"));

        assertEquals(204, controller.clear(7L).getStatusCode().value());

        assertNull(world.projectAiSettingsService.getOpenAiApiKeyEncrypted(7L));
        assertFalse(world.projectAiSettingsService.hasOpenAiApiKey(7L));
        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/7/members/10").size());
    }

    @Test
    void メンバーでなければForbiddenで何も保存しない() {
        world.member(10L);
        world.http.json("GET", "/api/internal/identity/projects/7/members/10", "false");

        assertThrows(ForbiddenException.class, () -> controller.status(7L));
        assertThrows(ForbiddenException.class, () -> controller.set(7L, new SetProjectOpenAiApiKeyRequest("sk")));
        assertThrows(ForbiddenException.class, () -> controller.clear(7L));

        assertTrue(world.projectAiSettingsRows.saved.isEmpty());
    }
}
