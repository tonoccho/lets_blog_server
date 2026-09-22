package com.letsblog.platform.controller;

import com.letsblog.platform.service.VscodeExtensionBuildService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        Path requestDir = tempDir.resolve("req-1");
        Files.createDirectories(requestDir);
        Path vsix = requestDir.resolve("letsblog-vscode-1.0.0.vsix");
        Files.writeString(vsix, "dummy vsix content");
        when(vscodeExtensionBuildService.buildAndGetVsix()).thenReturn(
                new VscodeExtensionBuildService.BuiltExtension(vsix, "letsblog-vscode-1.0.0.vsix", requestDir));

        VscodeExtensionController controller = new VscodeExtensionController(vscodeExtensionBuildService);
        ResponseEntity<?> response = controller.download();

        assertEquals(200, response.getStatusCode().value());
        String contentDisposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(contentDisposition != null && contentDisposition.contains("letsblog-vscode-1.0.0.vsix"));
    }

    /**
     * issue #1190: 並行ダウンロードで、先行リクエストがレスポンス本文を読み取っている最中に
     * 後続リクエストが同じビルド成果物を削除して500(FileNotFoundException)になっていた。
     * 修正後はリクエスト専用の出力先を後片付けするが、それはレスポンス本文の読み取りが
     * 完了した**後**でなければならない(読み取り中に削除すると同じ事象を再現してしまう)。
     */
    @Test
    void download_レスポンス本文を読み終えるまで後片付けされない() throws IOException {
        Path requestDir = tempDir.resolve("req-2");
        Files.createDirectories(requestDir);
        Path vsix = requestDir.resolve("letsblog-vscode-1.0.0.vsix");
        byte[] content = "dummy vsix content".getBytes(StandardCharsets.UTF_8);
        Files.write(vsix, content);
        VscodeExtensionBuildService.BuiltExtension built =
                new VscodeExtensionBuildService.BuiltExtension(vsix, "letsblog-vscode-1.0.0.vsix", requestDir);
        when(vscodeExtensionBuildService.buildAndGetVsix()).thenReturn(built);

        VscodeExtensionController controller = new VscodeExtensionController(vscodeExtensionBuildService);
        ResponseEntity<Resource> response = controller.download();
        Resource resource = response.getBody();

        try (InputStream in = resource.getInputStream()) {
            verify(vscodeExtensionBuildService, never()).cleanupAfterDownload(built);
            byte[] actual = in.readAllBytes();
            assertArrayEquals(content, actual);
            verify(vscodeExtensionBuildService, never()).cleanupAfterDownload(built);
        }

        verify(vscodeExtensionBuildService, times(1)).cleanupAfterDownload(built);
    }
}
