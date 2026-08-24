package com.letsblog.media.controller;

import com.letsblog.media.dto.DeleteComfyUiCheckpointCommand;
import com.letsblog.media.dto.InstallComfyUiCheckpointCommand;
import com.letsblog.media.service.ModelInstallJobRunner;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ComfyUiCheckpointControllerの回帰テスト(#573 stage2)。legacy-apiから転送されたBearerトークンを
 * ModelInstallJobRunnerへそのまま引き渡すことを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ComfyUiCheckpointControllerTest {

    @Mock
    private ModelInstallJobRunner modelInstallJobRunner;
    @Mock
    private HttpServletRequest request;

    private ComfyUiCheckpointController controller;

    @BeforeEach
    void setUp() {
        controller = new ComfyUiCheckpointController(modelInstallJobRunner, request);
    }

    @Test
    void install_Bearerトークンを転送してジョブランナーを起動し202を返す() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer abc123");

        ResponseEntity<Void> response = controller.install(
                new InstallComfyUiCheckpointCommand(10L, "https://example.com/model.safetensors", "model.safetensors"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(modelInstallJobRunner).runComfyUiDownload(
                10L, "https://example.com/model.safetensors", "model.safetensors", "Bearer abc123");
    }

    @Test
    void delete_Bearerトークンを転送してジョブランナーを起動し202を返す() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer xyz789");

        ResponseEntity<Void> response = controller.delete(new DeleteComfyUiCheckpointCommand(11L, "model.safetensors"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(modelInstallJobRunner).runComfyUiDelete(11L, "model.safetensors", "Bearer xyz789");
    }
}
