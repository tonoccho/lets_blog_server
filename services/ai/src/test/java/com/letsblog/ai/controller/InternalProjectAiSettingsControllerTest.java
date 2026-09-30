package com.letsblog.ai.controller;

import com.letsblog.ai.controller.InternalProjectAiSettingsController.ProjectConnectionUrlsResponse;
import com.letsblog.ai.service.ProjectAiSettingsService;
import com.letsblog.common.crypto.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/** 内部ブリッジ {@code /api/internal/ai/projects/{projectId}/connections}(issue #1503、media-serviceが参照)。 */
@ExtendWith(MockitoExtension.class)
class InternalProjectAiSettingsControllerTest {

    @Mock
    private ProjectAiSettingsService projectAiSettingsService;
    @Mock
    private CredentialCipher credentialCipher;

    private InternalProjectAiSettingsController controller() {
        return new InternalProjectAiSettingsController(projectAiSettingsService, credentialCipher);
    }

    @Test
    void connections_上書き値をそのまま返す() {
        when(projectAiSettingsService.getOllamaBaseUrl(7L)).thenReturn("http://gpu:11434/v1");
        when(projectAiSettingsService.getComfyuiBaseUrl(7L)).thenReturn("http://gpu:8188");

        ProjectConnectionUrlsResponse response = controller().connections(7L);

        assertEquals("http://gpu:11434/v1", response.ollamaBaseUrl());
        assertEquals("http://gpu:8188", response.comfyuiBaseUrl());
    }

    @Test
    void connections_未設定ならどちらもnull() {
        ProjectConnectionUrlsResponse response = controller().connections(7L);

        assertNull(response.ollamaBaseUrl());
        assertNull(response.comfyuiBaseUrl());
    }
}
