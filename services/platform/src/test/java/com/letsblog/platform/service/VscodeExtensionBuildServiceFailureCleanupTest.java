package com.letsblog.platform.service;

import com.letsblog.platform.service.VscodeExtensionBuildService.BuiltExtension;
import com.letsblog.platform.service.VscodeExtensionBuildService.VscodeExtensionBuildException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1378: ビルドが失敗したとき、リクエスト専用の出力ディレクトリが残らないことの検証。
 * 実際のnpm/vsceは呼ばず、build(Path, String)を差し替えて失敗を再現する。
 */
class VscodeExtensionBuildServiceFailureCleanupTest {

    @TempDir
    Path tempDir;

    private enum Mode { THROW_AFTER_PARTIAL_OUTPUT, SUCCEED_WITHOUT_VSIX, SUCCEED }

    private static final class FakeService extends VscodeExtensionBuildService {
        private final Mode mode;
        final List<Path> outputDirs = new ArrayList<>();

        FakeService(String source, String build, Mode mode) {
            super(source, build);
            this.mode = mode;
        }

        @Override
        void build(Path outputDir, String filename) {
            outputDirs.add(outputDir);
            try {
                Files.createDirectories(outputDir);
                switch (mode) {
                    case THROW_AFTER_PARTIAL_OUTPUT -> {
                        Files.writeString(outputDir.resolve("partial.tmp"), "x");
                        throw new VscodeExtensionBuildException("npm ci が失敗しました");
                    }
                    case SUCCEED_WITHOUT_VSIX -> { }
                    case SUCCEED -> Files.writeString(outputDir.resolve(filename), "vsix");
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private FakeService service(Mode mode) throws IOException {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("package.json"), "{\"version\":\"1.2.3\"}");
        return new FakeService(source.toString(), tempDir.resolve("build").toString(), mode);
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
        FakeService service = service(Mode.THROW_AFTER_PARTIAL_OUTPUT);

        assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);

        assertEquals(1, service.outputDirs.size());
        assertFalse(Files.exists(service.outputDirs.get(0)));
        assertEquals(0, outputEntryCount());
    }

    @Test
    void ビルド成功後にvsixが無いときも出力ディレクトリが残らない() throws Exception {
        FakeService service = service(Mode.SUCCEED_WITHOUT_VSIX);

        assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);

        assertFalse(Files.exists(service.outputDirs.get(0)));
        assertEquals(0, outputEntryCount());
    }

    @Test
    void 成功経路では出力ディレクトリは残り_cleanupAfterDownloadで削除される() throws Exception {
        FakeService service = service(Mode.SUCCEED);

        BuiltExtension built = service.buildAndGetVsix();

        assertTrue(Files.exists(built.vsixPath()));
        service.cleanupAfterDownload(built);
        assertFalse(Files.exists(built.requestOutputDir()));
    }
}
