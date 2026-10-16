package com.letsblog.media.service;

import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.dto.ImageProviderListResponse;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像生成プロバイダ(ComfyUI / ChatGPT)の選択。プロジェクトに設定が無ければComfyUI。
 *
 * <p>issue #1819: {@code ProjectServiceClient}・{@code ProjectImageSettingsService}は実物で、project-serviceを
 * フェイクHTTPサーバー、{@code project_image_settings}をリポジトリのmockが演じる。
 */
@DisplayName("ImageModelService: 画像生成プロバイダの選択")
class ImageModelServiceTest {

    private final MediaWorld world = new MediaWorld();
    private final ImageModelService service = world.imageModelService;

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    @DisplayName("プロジェクトを指定しなければComfyUI(project-serviceに問い合わせない)")
    void プロジェクト未指定() {
        assertEquals(ImageProvider.COMFYUI, service.getSelectedProvider(null));
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    @DisplayName("プロジェクトに設定が無ければComfyUI")
    void 設定が無い() {
        assertEquals(ImageProvider.COMFYUI, service.getSelectedProvider(1L));

        world.providerIs(1L, "  ");
        assertEquals(ImageProvider.COMFYUI, service.getSelectedProvider(1L));
    }

    @Test
    @DisplayName("保存されている値が未知のプロバイダなら例外にする")
    void 未知のプロバイダ() {
        world.providerIs(1L, "UNKNOWN");

        assertThrows(IllegalArgumentException.class, () -> service.getSelectedProvider(1L));
    }

    @Test
    @DisplayName("プロジェクトにChatGPTが設定されていればChatGPT")
    void ChatGPTを選択済み() {
        world.providerIs(1L, "CHATGPT");

        assertEquals(ImageProvider.CHATGPT, service.getSelectedProvider(1L));
    }

    @Test
    @DisplayName("存在しないプロジェクトは例外")
    void プロジェクトが存在しない() {
        world.projectMissing(1L);

        assertThrows(ProjectNotFoundException.class, () -> service.getSelectedProvider(1L));
        assertThrows(ProjectNotFoundException.class, () -> service.listProvidersForProject(1L));
        assertThrows(ProjectNotFoundException.class, () -> service.selectProvider(1L, "CHATGPT"));
    }

    @Test
    @DisplayName("プロバイダの一覧と、選択中のプロバイダ(未設定ならnull)を返す")
    void 一覧() {
        ImageProviderListResponse unset = service.listProvidersForProject(1L);
        assertEquals(List.of("COMFYUI", "CHATGPT"), unset.availableProviders());
        assertNull(unset.selected());

        world.providerIs(1L, "CHATGPT");
        assertEquals("CHATGPT", service.listProvidersForProject(1L).selected());
    }

    @Test
    @DisplayName("プロバイダを選ぶと保存され、選択結果の一覧を返す")
    void 選択する() {
        ImageProviderListResponse response = service.selectProvider(1L, "CHATGPT");

        assertEquals("CHATGPT", response.selected());
        assertEquals("CHATGPT", world.settings.get(1L).getImageProvider());
    }

    @Test
    @DisplayName("空のプロバイダを選ぶと、プロジェクト単位の上書きを解除する(ComfyUIへ戻す)")
    void 解除する() {
        world.providerIs(1L, "CHATGPT");

        assertNull(service.selectProvider(1L, null).selected());
        assertNull(world.settings.get(1L).getImageProvider());
    }

    @Test
    @DisplayName("未知のプロバイダを選ぶと例外にして、保存済みの選択を変えない")
    void 未知のプロバイダを選ぶ() {
        world.providerIs(1L, "CHATGPT");

        assertThrows(IllegalArgumentException.class, () -> service.selectProvider(1L, "nonsense"));

        assertEquals("CHATGPT", world.settings.get(1L).getImageProvider());
    }
}
