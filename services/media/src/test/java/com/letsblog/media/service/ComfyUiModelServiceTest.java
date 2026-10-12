package com.letsblog.media.service;

import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.client.ProjectServiceClient;
import com.letsblog.media.dto.ComfyUiCheckpointListResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ComfyUiModelServiceの回帰テスト。プロジェクト未選択時のグローバルデフォルト、選択チェックポイントの
 * 存在チェックなどの分岐を固定する。チェックポイントの導入・削除(startInstall / startDelete)は
 * ComfyUIをGPU PCなど外部のサーバーへ接続する構成にしたため機能ごと削除し(issue #1746)、
 * それらのテストも削除した。
 */
@ExtendWith(MockitoExtension.class)
class ComfyUiModelServiceTest {

    private static final String GLOBAL_DEFAULT = "v1-5-pruned-emaonly.safetensors";

    @Mock
    private ComfyUiClient comfyUiClient;
    @Mock
    private ProjectServiceClient projectServiceClient;
    @Mock
    private ProjectImageSettingsService projectImageSettingsService;

    private ComfyUiModelService service;

    @BeforeEach
    void setUp() {
        service = new ComfyUiModelService(
                comfyUiClient, projectServiceClient, projectImageSettingsService, GLOBAL_DEFAULT);
    }

    @Test
    void getSelectedCheckpoint_未選択ならグローバルデフォルト() {
        when(projectImageSettingsService.getComfyuiCheckpoint(1L)).thenReturn(null);

        String selected = service.getSelectedCheckpoint(1L);

        assertThat(selected).isEqualTo(GLOBAL_DEFAULT);
        verify(projectServiceClient).requireProjectExists(1L);
    }

    @Test
    void getSelectedCheckpoint_空文字もグローバルデフォルト扱い() {
        when(projectImageSettingsService.getComfyuiCheckpoint(1L)).thenReturn("  ");

        assertThat(service.getSelectedCheckpoint(1L)).isEqualTo(GLOBAL_DEFAULT);
    }

    @Test
    void getSelectedCheckpoint_選択済みならそれを返す() {
        when(projectImageSettingsService.getComfyuiCheckpoint(1L)).thenReturn("custom.safetensors");

        assertThat(service.getSelectedCheckpoint(1L)).isEqualTo("custom.safetensors");
    }

    @Test
    void getSelectedCheckpointOrGlobalDefault_projectIdがnullならグローバルデフォルト() {
        assertThat(service.getSelectedCheckpointOrGlobalDefault(null)).isEqualTo(GLOBAL_DEFAULT);
    }

    @Test
    void getSelectedCheckpointOrGlobalDefault_projectIdがあればプロジェクト設定を見る() {
        when(projectImageSettingsService.getComfyuiCheckpoint(2L)).thenReturn("custom.safetensors");

        assertThat(service.getSelectedCheckpointOrGlobalDefault(2L)).isEqualTo("custom.safetensors");
    }

    @Test
    void listCheckpointsForProject_ComfyUiの一覧と選択中を返す() {
        when(comfyUiClient.listCheckpoints(1L)).thenReturn(List.of("a.safetensors", "b.safetensors"));
        when(projectImageSettingsService.getComfyuiCheckpoint(1L)).thenReturn("a.safetensors");

        ComfyUiCheckpointListResponse response = service.listCheckpointsForProject(1L);

        assertThat(response.checkpoints()).containsExactly("a.safetensors", "b.safetensors");
        assertThat(response.selected()).isEqualTo("a.safetensors");
    }

    @Test
    void selectCheckpoint_存在するチェックポイントなら選択して保存する() {
        when(comfyUiClient.listCheckpoints(1L)).thenReturn(List.of("a.safetensors"));
        when(projectImageSettingsService.getComfyuiCheckpoint(1L)).thenReturn("a.safetensors");

        service.selectCheckpoint(1L, "a.safetensors");

        verify(projectImageSettingsService).setComfyuiCheckpoint(1L, "a.safetensors");
    }

    @Test
    void selectCheckpoint_存在しなければ例外() {
        when(comfyUiClient.listCheckpoints(1L)).thenReturn(List.of("a.safetensors"));

        assertThatThrownBy(() -> service.selectCheckpoint(1L, "missing.safetensors"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing.safetensors");
    }
}
