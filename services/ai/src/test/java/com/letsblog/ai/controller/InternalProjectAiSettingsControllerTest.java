package com.letsblog.ai.controller;

import com.letsblog.ai.controller.InternalProjectAiSettingsController.OpenAiApiKeyResponse;
import com.letsblog.ai.controller.InternalProjectAiSettingsController.ProjectConnectionUrlsResponse;
import com.letsblog.ai.testsupport.AiWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 内部ブリッジ {@code /api/internal/ai/projects/{projectId}/connections}(issue #1503、media-serviceが参照)。
 *
 * <p>issue #1824: ProjectAiSettingsServiceとCredentialCipherは実物で、DBはメモリ上のリポジトリで差し替える。
 */
class InternalProjectAiSettingsControllerTest {

    private AiWorld world;
    private InternalProjectAiSettingsController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        controller = new InternalProjectAiSettingsController(world.projectAiSettingsService, world.credentialCipher);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void connections_上書き値をそのまま返す() {
        world.projectAiSettingsService.setConnectionUrls(7L, "http://gpu:11434/v1", "http://gpu:8188");

        ProjectConnectionUrlsResponse response = controller.connections(7L);

        assertEquals("http://gpu:11434/v1", response.ollamaBaseUrl());
        assertEquals("http://gpu:8188", response.comfyuiBaseUrl());
    }

    @Test
    void connections_未設定ならどちらもnull() {
        ProjectConnectionUrlsResponse response = controller.connections(7L);

        assertNull(response.ollamaBaseUrl());
        assertNull(response.comfyuiBaseUrl());
    }

    /** media-serviceが画像生成でプロジェクトのChatGPTキーを解決する内部ブリッジ(issue #1521)。 */
    @Test
    void openAiApiKey_保存済みのキーを復号して返す() {
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-project"));

        assertEquals("sk-project", controller.openAiApiKey(7L).apiKey());
    }

    @Test
    void openAiApiKey_未設定ならnull() {
        assertNull(controller.openAiApiKey(7L).apiKey());
    }

    @Test
    void openAiApiKey_空のバイト列は未設定として扱いnull() {
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(7L, new byte[0]);

        assertNull(controller.openAiApiKey(7L).apiKey());
    }

    @Test
    void openAiApiKey_応答のtoStringにキーの値を含めない() {
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-secret-value"));

        OpenAiApiKeyResponse response = controller.openAiApiKey(7L);

        assertEquals("sk-secret-value", response.apiKey());
        assertFalse(response.toString().contains("sk-secret-value"));
    }

    @Test
    void openAiApiKey_未設定の応答のtoStringはnullと表示する() {
        assertEquals("OpenAiApiKeyResponse[apiKey=null]", controller.openAiApiKey(7L).toString());
    }
}
