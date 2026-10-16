package com.letsblog.platform.service;

import com.letsblog.platform.service.VscodeExtensionBuildService.BuiltExtension;
import com.letsblog.platform.service.VscodeExtensionBuildService.VscodeExtensionBuildException;
import com.letsblog.platform.testsupport.FakeBin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1378: ビルドが失敗したとき、リクエスト専用の出力ディレクトリが残らないことの検証。
 *
 * <p>issue #1825: npm / npx は{@code src/test/fake-bin}の代役の実行ファイルで、{@link VscodeExtensionBuildService}の
 * 実物がそのまま呼ぶ。失敗は代役を失敗させて再現する(サービスを継承して{@code build}を差し替えない)。
 */
class VscodeExtensionBuildServiceFailureCleanupTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetFakeBin() {
        FakeBin.reset();
    }

    private VscodeExtensionBuildService service() throws IOException {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("package.json"), "{\"version\":\"1.2.3\"}");
        return new VscodeExtensionBuildService(source.toString(), tempDir.resolve("build").toString());
    }

    private long outputEntryCount() throws IOException {
        Path root = tempDir.resolve("build").resolve("output");
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.count();
        }
    }

    @Test
    void build例外のとき出力ディレクトリが残らず例外は呼び出し元へ伝わる() throws Exception {
        VscodeExtensionBuildService service = service();
        FakeBin.fail("npx", "vsce: packaging failed");

        assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);

        assertEquals(0, outputEntryCount());
    }

    @Test
    void ビルド成功後にvsixが無いときも出力ディレクトリが残らない() throws Exception {
        VscodeExtensionBuildService service = service();
        FakeBin.npxWritesNoOutput();

        assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);

        assertEquals(0, outputEntryCount());
    }

    @Test
    void 成功経路では出力ディレクトリは残り_cleanupAfterDownloadで削除される() throws Exception {
        VscodeExtensionBuildService service = service();

        BuiltExtension built = service.buildAndGetVsix();

        assertTrue(Files.exists(built.vsixPath()));
        service.cleanupAfterDownload(built);
        assertFalse(Files.exists(built.requestOutputDir()));
    }
}
