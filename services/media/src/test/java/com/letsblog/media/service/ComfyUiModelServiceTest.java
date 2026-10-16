package com.letsblog.media.service;

import com.letsblog.media.domain.ProjectImageSettings;
import com.letsblog.media.dto.ComfyUiCheckpointListResponse;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

/**
 * ComfyUiModelServiceのチェックポイント選択ロジック(issue #573 stage3)。
 *
 * <p>プロジェクト単位のチェックポイント選択・グローバルデフォルトへのフォールバック・
 * 存在チェックなどの分岐を固定する。チェックポイントの導入・削除(startInstall / startDelete)は
 * ComfyUIをGPU PCなど外部のサーバーへ接続する構成にしたため機能ごと削除し(issue #1746)、
 * それらのテストも削除した。
 *
 * <p>issue #1819: {@code ComfyUiClient}・{@code ProjectServiceClient}・{@code ProjectImageSettingsService}は
 * 実物で、ComfyUI・project-service・ai-service・platform-serviceをフェイクHTTPサーバー、
 * {@code project_image_settings}をリポジトリのmock(メモリ上の行)が演じる。
 */
class ComfyUiModelServiceTest {

    private static final String GLOBAL_DEFAULT = MediaWorld.GLOBAL_CHECKPOINT;

    private final MediaWorld world = new MediaWorld();
    private final ComfyUiModelService service = world.comfyUiModelService;

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    void getSelectedCheckpoint_未選択ならグローバルデフォルト() {
        String selected = service.getSelectedCheckpoint(1L);

        assertThat(selected).isEqualTo(GLOBAL_DEFAULT);
        assertThat(world.http.requests("GET", "/api/internal/project/projects/1")).hasSize(1);
    }

    @Test
    void getSelectedCheckpoint_空文字もグローバルデフォルト扱い() {
        world.settingsFor(1L).setComfyuiCheckpoint("  ");

        assertThat(service.getSelectedCheckpoint(1L)).isEqualTo(GLOBAL_DEFAULT);
    }

    @Test
    void getSelectedCheckpoint_選択済みならそれを返す() {
        world.settingsFor(1L).setComfyuiCheckpoint("custom.safetensors");

        assertThat(service.getSelectedCheckpoint(1L)).isEqualTo("custom.safetensors");
    }

    @Test
    void getSelectedCheckpoint_存在しないプロジェクトは例外() {
        world.projectMissing(1L);

        assertThatThrownBy(() -> service.getSelectedCheckpoint(1L)).isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void getSelectedCheckpointOrGlobalDefault_projectIdがnullならグローバルデフォルト() {
        assertThat(service.getSelectedCheckpointOrGlobalDefault(null)).isEqualTo(GLOBAL_DEFAULT);
        assertThat(world.http.requests("GET", "/api/internal/project/.*")).isEmpty();
    }

    @Test
    void getSelectedCheckpointOrGlobalDefault_projectIdがあればプロジェクト設定を見る() {
        world.settingsFor(2L).setComfyuiCheckpoint("custom.safetensors");

        assertThat(service.getSelectedCheckpointOrGlobalDefault(2L)).isEqualTo("custom.safetensors");
    }

    @Test
    void listCheckpointsForProject_ComfyUiの一覧と選択中を返す() {
        world.comfyObjectInfo(List.of("a.safetensors", "b.safetensors"), List.of(), List.of(), List.of());
        world.settingsFor(1L).setComfyuiCheckpoint("a.safetensors");

        ComfyUiCheckpointListResponse response = service.listCheckpointsForProject(1L);

        assertThat(response.checkpoints()).containsExactly("a.safetensors", "b.safetensors");
        assertThat(response.selected()).isEqualTo("a.safetensors");
    }

    @Test
    void selectCheckpoint_存在するチェックポイントなら選択して保存する() {
        world.comfyObjectInfo(List.of("a.safetensors"), List.of(), List.of(), List.of());

        ComfyUiCheckpointListResponse response = service.selectCheckpoint(1L, "a.safetensors");

        ProjectImageSettings saved = world.settings.get(1L);
        assertThat(saved.getComfyuiCheckpoint()).isEqualTo("a.safetensors");
        assertThat(response.selected()).isEqualTo("a.safetensors");
    }

    @Test
    void selectCheckpoint_存在しなければ例外で何も保存しない() {
        world.comfyObjectInfo(List.of("a.safetensors"), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> service.selectCheckpoint(1L, "missing.safetensors"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing.safetensors");

        verify(world.projectImageSettingsRepository, never()).save(any());
    }
}
