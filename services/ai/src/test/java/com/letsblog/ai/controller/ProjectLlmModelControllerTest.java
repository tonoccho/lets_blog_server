package com.letsblog.ai.controller;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.dto.LlmModelListResponse;
import com.letsblog.ai.dto.LlmProviderListResponse;
import com.letsblog.ai.dto.ReviewStepSettingsResponse;
import com.letsblog.ai.dto.SelectLlmModelRequest;
import com.letsblog.ai.dto.SelectLlmProviderRequest;
import com.letsblog.ai.dto.SelectReviewStepModelRequest;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.testsupport.AiWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProjectLlmModelControllerの回帰テスト(issue #574、legacy-apiのProjectAiModelControllerから
 * {@code /llm/**}部分を分割したもの)。requireAdmin()を呼び出したうえでサービスへ委譲すること、
 * 認可拒否時にサービスを呼び出さないことを検証する。
 *
 * <p>issue #1824: LlmModelService・ReviewStepModelService・AdminAuthorizationServiceは実物で、
 * identity-service・platform-service・LLMプロバイダーの一覧取得はHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class ProjectLlmModelControllerTest {

    private AiWorld world;
    private ProjectLlmModelController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        controller = new ProjectLlmModelController(
                world.llmModelService, world.adminAuthorizationService, world.reviewStepModelService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void adminWithOpenAiProject() {
        world.admin();
        world.projectAiSettingsService.setLlmProvider(1L, "OPENAI");
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(1L, world.credentialCipher.encrypt("sk-project"));
        world.http.json("GET", "/cloud/v1/models", "{\"data\":[{\"id\":\"gpt-4o-mini\"},{\"id\":\"gpt-4o\"}]}");
    }

    private void nonAdmin() {
        world.member(10L);
    }

    private void systemConfigs() {
        String root = world.http.baseUrl();
        world.llmConfig(AiProvider.OLLAMA, root + "/v1", "", "o", List.of("o"), true);
        world.llmConfig(AiProvider.OPENAI, null, "", "gpt-4o-mini", List.of("gpt-4o-mini"), false);
        world.llmConfig(AiProvider.CLAUDE, null, "", "claude", List.of("claude"), false);
    }

    @Test
    void listLlmModels_認可後にサービスへ委譲する() {
        adminWithOpenAiProject();
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OPENAI, "gpt-4o-mini");

        LlmModelListResponse response = controller.listLlmModels(1L);

        assertEquals("gpt-4o-mini", response.selected());
        assertEquals(List.of("gpt-4o-mini", "gpt-4o"), response.availableModels());
        assertEquals(1, world.http.requests("GET", "/api/identity/me").size());
    }

    @Test
    void listLlmModels_認可拒否ならForbidden() {
        nonAdmin();

        assertThrows(ForbiddenException.class, () -> controller.listLlmModels(1L));

        assertTrue(world.http.requests("GET", "/cloud/v1/models").isEmpty());
    }

    @Test
    void selectLlmModel_認可後にサービスへ委譲する() {
        adminWithOpenAiProject();

        LlmModelListResponse response = controller.selectLlmModel(1L, new SelectLlmModelRequest("gpt-4o"));

        assertEquals("gpt-4o", response.selected());
        assertEquals("gpt-4o", world.projectAiSettingsService.getLlmModel(1L, AiProvider.OPENAI));
    }

    @Test
    void selectLlmModel_認可拒否ならForbiddenで保存しない() {
        nonAdmin();

        assertThrows(ForbiddenException.class, () -> controller.selectLlmModel(1L, new SelectLlmModelRequest("gpt-4o")));

        assertTrue(world.projectAiSettingsRows.saved.isEmpty());
    }

    @Test
    void listLlmProvider_認可後にサービスへ委譲する() {
        world.admin();

        LlmProviderListResponse response = controller.listLlmProvider(1L);

        assertEquals(3, response.availableProviders().size());
        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), response.availableProviders());
    }

    @Test
    void listLlmProvider_認可拒否ならForbidden() {
        nonAdmin();

        assertThrows(ForbiddenException.class, () -> controller.listLlmProvider(1L));
    }

    @Test
    void selectLlmProvider_認可後にサービスへ委譲する() {
        world.admin();

        LlmProviderListResponse response = controller.selectLlmProvider(1L, new SelectLlmProviderRequest("CLAUDE"));

        assertEquals("CLAUDE", response.selected());
        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), response.availableProviders());
        assertEquals("CLAUDE", world.projectAiSettingsService.getLlmProvider(1L));
    }

    @Test
    void selectLlmProvider_認可拒否ならForbiddenで保存しない() {
        nonAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller.selectLlmProvider(1L, new SelectLlmProviderRequest("CLAUDE")));

        assertTrue(world.projectAiSettingsRows.saved.isEmpty());
    }

    @Test
    void listReviewStepSettings_認可後にサービスへ委譲する() {
        world.admin();
        systemConfigs();
        world.http.json("GET", "/api/tags", "{\"models\":[{\"name\":\"o\"}]}");

        ReviewStepSettingsResponse response = controller.listReviewStepSettings(1L);

        assertEquals(5, response.steps().size());
        assertEquals("JAPANESE", response.steps().get(0).stepKey());
        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), response.availableProviders());
    }

    @Test
    void listReviewStepSettings_認可拒否ならForbidden() {
        nonAdmin();

        assertThrows(ForbiddenException.class, () -> controller.listReviewStepSettings(1L));
    }

    @Test
    void updateReviewStepSetting_認可後にサービスへ委譲する() {
        world.admin();
        systemConfigs();

        ReviewStepSettingsResponse response = controller.updateReviewStepSetting(
                1L, ReviewStepKey.STYLE.name(), new SelectReviewStepModelRequest("CLAUDE", "claude"));

        assertEquals(1, world.reviewStepRows.saved.size());
        assertEquals(ReviewStepKey.STYLE, world.reviewStepRows.saved.get(0).getStepKey());
        assertEquals("CLAUDE", world.reviewStepRows.saved.get(0).getLlmProvider());
        assertEquals("claude", world.reviewStepRows.saved.get(0).getLlmModel());
        assertEquals("CLAUDE", response.steps().stream()
                .filter(step -> step.stepKey().equals("STYLE")).findFirst().orElseThrow().provider());
    }

    @Test
    void updateReviewStepSetting_認可拒否ならForbiddenで保存しない() {
        nonAdmin();

        assertThrows(
                ForbiddenException.class,
                () -> controller.updateReviewStepSetting(
                        1L, ReviewStepKey.STYLE.name(), new SelectReviewStepModelRequest("CLAUDE", "gpt-4o")));

        assertTrue(world.reviewStepRows.saved.isEmpty());
    }
}
