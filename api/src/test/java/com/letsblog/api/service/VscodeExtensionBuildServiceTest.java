package com.letsblog.api.service;

import com.letsblog.api.service.VscodeExtensionBuildService.BuiltExtension;
import com.letsblog.api.service.VscodeExtensionBuildService.VscodeExtensionBuildException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 実際のnpm/vsce呼び出しはビルド環境依存のため単体テストでは検証しない
 * (Docker環境での実機ビルド確認をplan/todoに明記済み)。ここではキャッシュ判定と
 * ソース未検出時のエラーハンドリングという、プロセス起動前に完結するロジックのみ検証する。
 */
class VscodeExtensionBuildServiceTest {

    @TempDir
    Path tempDir;

    private Path sourceDir;
    private Path buildDir;

    private VscodeExtensionBuildService service() {
        sourceDir = tempDir.resolve("source");
        buildDir = tempDir.resolve("build");
        return new VscodeExtensionBuildService(sourceDir.toString(), buildDir.toString());
    }

    private void writePackageJson(String version) throws IOException {
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("package.json"), "{\"name\":\"letsblog-vscode\",\"version\":\"" + version + "\"}");
    }

    @Test
    void buildAndGetVsix_キャッシュ済みのvsixがあればビルドを実行せず返す() throws IOException {
        VscodeExtensionBuildService service = service();
        writePackageJson("1.2.3");
        Path outputDir = buildDir.resolve("output");
        Files.createDirectories(outputDir);
        Path cached = outputDir.resolve("letsblog-vscode-1.2.3.vsix");
        Files.writeString(cached, "dummy");

        BuiltExtension result = service.buildAndGetVsix();

        assertEquals(cached, result.vsixPath());
        assertEquals("letsblog-vscode-1.2.3.vsix", result.filename());
    }

    @Test
    void buildAndGetVsix_ソースのpackage_jsonが見つからない場合は例外() {
        VscodeExtensionBuildService service = service();
        // sourceDirを作成しない = package.jsonが存在しない状態

        assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);
    }

    @Test
    void buildAndGetVsix_versionフィールドがないpackage_jsonの場合は例外() throws IOException {
        VscodeExtensionBuildService service = service();
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("package.json"), "{\"name\":\"letsblog-vscode\"}");

        VscodeExtensionBuildException exception =
                assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);
        assertTrue(exception.getMessage().contains("version"));
    }
}
