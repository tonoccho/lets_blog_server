package com.letsblog.media.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.letsblog.media.dto.ProjectImageSettingsResponse;
import com.letsblog.media.dto.UpdateArticleImageResizeDefaultRequest;
import com.letsblog.media.dto.UpdateImageContentFilterSettingsRequest;
import com.letsblog.media.dto.UpdateImageGenerationPromptDefaultsRequest;
import com.letsblog.media.dto.UpdateImageGenerationSizeDefaultsRequest;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.ProjectNotFoundException;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ProjectImageSettingsController}(issue #1820)。認可・設定サービス・project-serviceクライアントは実物で、
 * project-service・identity-serviceをフェイクHTTPサーバーが、{@code project_image_settings}テーブルをメモリ上の行が演じる。
 */
class ProjectImageSettingsControllerTest {

    private final MediaWorld world = new MediaWorld();
    private ProjectImageSettingsController controller;

    @BeforeEach
    void setUp() {
        world.actingAsAdmin(1L);
        controller = new ProjectImageSettingsController(
                world.projectImageSettingsService, world.projectServiceClient, world.adminAuthorizationService);
    }

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    void get_未設定のプロジェクトは全フィールドがnull() {
        ProjectImageSettingsResponse response = controller.get(5L);

        assertEquals(5L, response.projectId());
        assertNull(response.imageProvider());
        assertNull(response.defaultNegativePrompt());
        assertNull(response.blockSexualContent());
    }

    @Test
    void get_設定済みのプロジェクトはその値を返す() {
        world.settingsFor(5L).setImageProvider("COMFYUI");

        assertEquals("COMFYUI", controller.get(5L).imageProvider());
    }

    @Test
    void get_プロジェクトが無ければProjectNotFoundException() {
        world.projectMissing(5L);

        assertThrows(ProjectNotFoundException.class, () -> controller.get(5L));
    }

    @Test
    void get_メンバーでもadminでもなければ403() {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/5/members/10", "false");

        assertThrows(ForbiddenException.class, () -> controller.get(5L));
    }

    @Test
    void 更新はadmin以外なら403で保存しない() {
        world.actingAsUser(10L);

        assertThrows(ForbiddenException.class, () -> controller.updateImageGenerationPromptDefaults(
                5L, new UpdateImageGenerationPromptDefaultsRequest("n", "q")));
        assertThrows(ForbiddenException.class, () -> controller.updateImageGenerationSizeDefaults(
                5L, new UpdateImageGenerationSizeDefaultsRequest(512, 512)));
        assertThrows(ForbiddenException.class, () -> controller.updateArticleImageResizeDefault(
                5L, new UpdateArticleImageResizeDefaultRequest(1300)));
        assertThrows(ForbiddenException.class, () -> controller.updateImageContentFilterSettings(
                5L, new UpdateImageContentFilterSettingsRequest(true, false, true)));

        assertTrue(world.settings.isEmpty());
    }

    @Test
    void 更新はプロジェクトが無ければProjectNotFoundExceptionで保存しない() {
        world.projectMissing(5L);

        assertThrows(ProjectNotFoundException.class, () -> controller.updateImageGenerationPromptDefaults(
                5L, new UpdateImageGenerationPromptDefaultsRequest("n", "q")));
        assertThrows(ProjectNotFoundException.class, () -> controller.updateImageGenerationSizeDefaults(
                5L, new UpdateImageGenerationSizeDefaultsRequest(512, 512)));
        assertThrows(ProjectNotFoundException.class, () -> controller.updateArticleImageResizeDefault(
                5L, new UpdateArticleImageResizeDefaultRequest(1300)));
        assertThrows(ProjectNotFoundException.class, () -> controller.updateImageContentFilterSettings(
                5L, new UpdateImageContentFilterSettingsRequest(true, false, true)));

        assertTrue(world.settings.isEmpty());
    }

    @Test
    void プロンプトの既定は値をそのまま保存する() {
        ProjectImageSettingsResponse response = controller.updateImageGenerationPromptDefaults(
                5L, new UpdateImageGenerationPromptDefaultsRequest("blurry", "masterpiece"));

        assertEquals("blurry", response.defaultNegativePrompt());
        assertEquals("masterpiece", response.defaultQualityPrompt());
        assertEquals("blurry", world.settings.get(5L).getDefaultNegativePrompt());
    }

    @Test
    void プロンプトの既定は空文字と空白とnullを上書きなし_nullとして保存する() {
        ProjectImageSettingsResponse blank = controller.updateImageGenerationPromptDefaults(
                5L, new UpdateImageGenerationPromptDefaultsRequest("", "  "));
        assertNull(blank.defaultNegativePrompt());
        assertNull(blank.defaultQualityPrompt());

        ProjectImageSettingsResponse nulls = controller.updateImageGenerationPromptDefaults(
                5L, new UpdateImageGenerationPromptDefaultsRequest(null, null));
        assertNull(nulls.defaultNegativePrompt());
        assertNull(nulls.defaultQualityPrompt());
    }

    @Test
    void サイズの既定を保存する() {
        ProjectImageSettingsResponse response = controller.updateImageGenerationSizeDefaults(
                5L, new UpdateImageGenerationSizeDefaultsRequest(512, 768));

        assertEquals(512, response.defaultGeneratedImageWidth());
        assertEquals(768, response.defaultGeneratedImageHeight());
    }

    @Test
    void 記事画像のリサイズの既定を保存する() {
        assertEquals(1300, controller.updateArticleImageResizeDefault(
                5L, new UpdateArticleImageResizeDefaultRequest(1300)).defaultArticleImageLongEdgePx());
    }

    @Test
    void 不適切コンテンツのフィルタ設定を保存する() {
        ProjectImageSettingsResponse response = controller.updateImageContentFilterSettings(
                5L, new UpdateImageContentFilterSettingsRequest(true, false, null));

        assertEquals(true, response.blockSexualContent());
        assertEquals(false, response.blockViolentContent());
        assertNull(response.blockDiscriminatoryContent());
    }
}
