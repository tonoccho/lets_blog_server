package com.letsblog.platform.controller;

import com.letsblog.platform.service.VscodeExtensionBuildService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * legacy-apiのVscodeExtensionControllerTestと同じ観点をplatform-serviceへ移設したもの
 * (issue #696、C10-4)。
 */
@ExtendWith(MockitoExtension.class)
class VscodeExtensionControllerTest {

    @Mock
    private VscodeExtensionBuildService vscodeExtensionBuildService;

    @TempDir
    Path tempDir;

    @Test
    void download_ビルド結果をattachmentとして返す() throws IOException {
        Path vsix = tempDir.resolve("letsblog-vscode-1.0.0.vsix");
        Files.writeString(vsix, "dummy vsix content");
        when(vscodeExtensionBuildService.buildAndGetVsix())
                .thenReturn(new VscodeExtensionBuildService.BuiltExtension(vsix, "letsblog-vscode-1.0.0.vsix"));

        VscodeExtensionController controller = new VscodeExtensionController(vscodeExtensionBuildService);
        ResponseEntity<?> response = controller.download();

        assertEquals(200, response.getStatusCode().value());
        String contentDisposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(contentDisposition != null && contentDisposition.contains("letsblog-vscode-1.0.0.vsix"));
    }
}
