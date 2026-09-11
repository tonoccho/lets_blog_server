package com.letsblog.media.controller;

import com.letsblog.media.dto.DeleteComfyUiCheckpointCommand;
import com.letsblog.media.dto.InstallComfyUiCheckpointCommand;
import com.letsblog.media.service.ModelInstallJobRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * ComfyUiCheckpointControllerの回帰テスト(#573 stage2)。
 *
 * <p>issue #1083: 以前は呼び出し元のBearerトークンをModelInstallJobRunnerへそのまま
 * 引き渡していたが、非同期ジョブの生存期間全体でこのトークンを使い回すことが根本原因
 * だったため廃止した。GenerationJob更新の認証はGenerationJobClient側のClient
 * Credentialsトークンに委ねる(このコントローラ・ジョブランナーはもはやBearerトークンを
 * 保持しない)。
 */
@ExtendWith(MockitoExtension.class)
class ComfyUiCheckpointControllerTest {

    @Mock
    private ModelInstallJobRunner modelInstallJobRunner;

    private ComfyUiCheckpointController controller;

    @BeforeEach
    void setUp() {
        controller = new ComfyUiCheckpointController(modelInstallJobRunner);
    }

    @Test
    void install_ジョブランナーを起動し202を返す() {
        ResponseEntity<Void> response = controller.install(
                new InstallComfyUiCheckpointCommand(10L, "https://example.com/model.safetensors", "model.safetensors"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(modelInstallJobRunner).runComfyUiDownload(
                10L, "https://example.com/model.safetensors", "model.safetensors");
    }

    @Test
    void delete_ジョブランナーを起動し202を返す() {
        ResponseEntity<Void> response = controller.delete(new DeleteComfyUiCheckpointCommand(11L, "model.safetensors"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(modelInstallJobRunner).runComfyUiDelete(11L, "model.safetensors");
    }
}
