package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.client.GenerationJobClient;
import com.letsblog.media.client.GenerationJobSummary;
import com.letsblog.media.client.ProjectServiceClient;
import com.letsblog.media.dto.ComfyUiCheckpointListResponse;
import com.letsblog.media.dto.GenerationJobResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ComfyUiModelServiceの回帰テスト。issue #1083でModelInstallJobRunner#runComfyUiDownload/
 * runComfyUiDeleteからbearerToken引数を削除した際にこのクラスの呼び出し箇所も追従したため、
 * 既存の分岐(プロジェクト未選択時のグローバルデフォルト、選択チェックポイントの存在チェック、
 * downloadUrlのスキーム検証)をあわせて固定する。
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
    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private ModelInstallJobRunner modelInstallJobRunner;
    @Mock
    private HttpServletRequest request;

    private ComfyUiModelService service;

    @BeforeEach
    void setUp() {
        service = new ComfyUiModelService(
                comfyUiClient, projectServiceClient, projectImageSettingsService, generationJobClient,
                modelInstallJobRunner, new ObjectMapper(), request, GLOBAL_DEFAULT);
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

    @Test
    void startInstall_httpのURLならジョブを作成してランナーを起動する() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer token");
        when(generationJobClient.create(eq("comfyui_checkpoint_download"), anyString(), eq("Bearer token")))
                .thenReturn(new GenerationJobSummary(
                        10L, "comfyui_checkpoint_download", "running", LocalDateTime.now(), LocalDateTime.now()));

        GenerationJobResponse response = service.startInstall("https://example.com/model.safetensors", "model.safetensors");

        assertThat(response.id()).isEqualTo(10L);
        verify(modelInstallJobRunner).runComfyUiDownload(10L, "https://example.com/model.safetensors", "model.safetensors");
    }

    @Test
    void startInstall_http以外のURLは例外() {
        assertThatThrownBy(() -> service.startInstall("ftp://example.com/model.safetensors", "model.safetensors"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startInstall_スキームが無いURLも例外() {
        assertThatThrownBy(() -> service.startInstall("example.com/model.safetensors", "model.safetensors"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startDelete_ジョブを作成してランナーを起動する() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer token");
        when(generationJobClient.create(eq("comfyui_checkpoint_delete"), anyString(), eq("Bearer token")))
                .thenReturn(new GenerationJobSummary(
                        11L, "comfyui_checkpoint_delete", "running", LocalDateTime.now(), LocalDateTime.now()));

        GenerationJobResponse response = service.startDelete("model.safetensors");

        assertThat(response.id()).isEqualTo(11L);
        verify(modelInstallJobRunner).runComfyUiDelete(11L, "model.safetensors");
    }

    @Test
    void startDelete_requestPayloadのJSON変換に失敗しても空オブジェクトとして続行する(@org.mockito.Mock ObjectMapper failingObjectMapper)
            throws Exception {
        when(failingObjectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        ComfyUiModelService serviceWithFailingMapper = new ComfyUiModelService(
                comfyUiClient, projectServiceClient, projectImageSettingsService, generationJobClient,
                modelInstallJobRunner, failingObjectMapper, request, GLOBAL_DEFAULT);
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer token");
        when(generationJobClient.create(eq("comfyui_checkpoint_delete"), eq("{}"), eq("Bearer token")))
                .thenReturn(new GenerationJobSummary(
                        12L, "comfyui_checkpoint_delete", "running", LocalDateTime.now(), LocalDateTime.now()));

        GenerationJobResponse response = serviceWithFailingMapper.startDelete("model.safetensors");

        assertThat(response.id()).isEqualTo(12L);
    }
}
