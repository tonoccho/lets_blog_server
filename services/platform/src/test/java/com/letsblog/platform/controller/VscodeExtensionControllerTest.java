package com.letsblog.platform.controller;

import com.letsblog.platform.config.GlobalExceptionHandler;
import com.letsblog.platform.service.VscodeExtensionBuildService;
import com.letsblog.platform.testsupport.FakeBin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * legacy-apiのVscodeExtensionControllerTestと同じ観点をplatform-serviceへ移設したもの
 * (issue #696、C10-4)。
 *
 * <p>issue #1825: コントローラとVscodeExtensionBuildServiceは実物で、Springの{@link MockMvc}(スタンドアロン)で
 * ダウンロードする。ビルドで呼ばれるnpm / npxだけが{@code src/test/fake-bin}の代役の実行ファイル。
 */
class VscodeExtensionControllerTest {

    @TempDir
    Path tempDir;

    private VscodeExtensionBuildService service;
    private MockMvc mvc;
    private Path buildDir;

    @BeforeEach
    void setUp() throws IOException {
        FakeBin.reset();
        Path source = tempDir.resolve("source");
        buildDir = tempDir.resolve("build");
        Files.createDirectories(source);
        Files.writeString(source.resolve("package.json"), "{\"name\":\"letsblog-vscode\",\"version\":\"1.0.0\"}");
        service = new VscodeExtensionBuildService(source.toString(), buildDir.toString());
        mvc = MockMvcBuilders.standaloneSetup(new VscodeExtensionController(service))
                .setControllerAdvice(new GlobalExceptionHandler(
                        new org.springframework.boot.servlet.autoconfigure.MultipartProperties()))
                .build();
    }

    @Test
    void download_ビルド結果をattachmentとして返す() throws Exception {
        MvcResult result = mvc.perform(get("/api/system/vscode-extension")).andExpect(status().isOk()).andReturn();

        String contentDisposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(contentDisposition != null && contentDisposition.contains("letsblog-vscode-1.0.0.vsix"));
        assertEquals("fake vsix\n", result.getResponse().getContentAsString());
    }

    /**
     * issue #1190: 並行ダウンロードで、先行リクエストがレスポンス本文を読み取っている最中に
     * 後続リクエストが同じビルド成果物を削除して500(FileNotFoundException)になっていた。
     * 修正後はリクエスト専用の出力先を後片付けするが、それはレスポンス本文の読み取りが
     * 完了した**後**でなければならない(読み取り中に削除すると同じ事象を再現してしまう)。
     */
    @Test
    void download_レスポンス本文を読み終えるまで後片付けされない() throws IOException {
        var controller = new VscodeExtensionController(service);
        var response = controller.download();
        var resource = response.getBody();
        Path vsix = resource.getFile().toPath();
        Path requestDir = vsix.getParent();

        try (InputStream in = resource.getInputStream()) {
            assertTrue(Files.exists(requestDir), "読み取り前に削除されない");
            byte[] actual = in.readAllBytes();
            assertEquals("fake vsix\n", new String(actual, StandardCharsets.UTF_8));
            assertTrue(Files.exists(requestDir), "読み取り中は削除されない");
        }

        assertFalse(Files.exists(requestDir), "読み取りを終えて閉じたら後片付けされる");
    }

    @Test
    void ビルドが失敗したら出力先を残さず例外が伝わる() throws Exception {
        FakeBin.fail("npm", "npm ERR! boom");

        // VscodeExtensionBuildExceptionは専用のハンドラが無いため、例外がそのまま伝わる。
        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mvc.perform(get("/api/system/vscode-extension")));

        assertTrue(thrown.getCause() instanceof VscodeExtensionBuildService.VscodeExtensionBuildException,
                String.valueOf(thrown));
        assertFalse(Files.exists(buildDir.resolve("output")) && Files.list(buildDir.resolve("output")).findAny().isPresent());
    }
}
