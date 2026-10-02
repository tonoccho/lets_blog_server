package com.letsblog.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1491: Penpotプラグイン / MCPサーバーをソース一式のZipとして配る。
 * node_modulesと既存のビルド生成物は含めず、package-lock.jsonは含める。
 */
class SourceArchiveServiceTest {

    @TempDir
    Path tempDir;

    private Path penpot;
    private Path mcp;

    private void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private SourceArchiveService service() throws IOException {
        penpot = tempDir.resolve("penpot-plugin");
        mcp = tempDir.resolve("mcp-server");
        write(penpot, "setup.sh", "#!/bin/bash");
        write(penpot, "README.md", "readme");
        write(penpot, "package.json", "{}");
        write(penpot, "package-lock.json", "{}");
        write(penpot, "manifest.json", "{}");
        write(penpot, "src/plugin.ts", "x");
        write(penpot, "plugin.js", "built");
        write(penpot, "ui.js", "built");
        write(penpot, "node_modules/esbuild/index.js", "dep");
        write(mcp, "setup.sh", "#!/bin/bash");
        write(mcp, "package-lock.json", "{}");
        write(mcp, "src/server.js", "x");
        write(mcp, "src/plugin.js", "not a build output here");
        write(mcp, ".env", "SECRET=1");
        write(mcp, ".env.example", "A=1");
        write(mcp, "logs/combined.log", "log");
        write(mcp, "debug.log", "log");
        write(mcp, "coverage/lcov.info", "cov");
        write(mcp, "node_modules/express/index.js", "dep");
        return new SourceArchiveService(penpot.toString(), mcp.toString());
    }

    private List<String> entries(SourceArchiveService.SourceArchive archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive.bytes()))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }

    @Test
    void penpotプラグインはソース一式を含みnode_modulesとビルド生成物を含まない() throws IOException {
        SourceArchiveService.SourceArchive archive = service().penpotPlugin();

        assertEquals("letsblog-penpot-plugin.zip", archive.filename());
        List<String> names = entries(archive);
        assertTrue(names.contains("letsblog-penpot-plugin/setup.sh"));
        assertTrue(names.contains("letsblog-penpot-plugin/README.md"));
        assertTrue(names.contains("letsblog-penpot-plugin/package-lock.json"));
        assertTrue(names.contains("letsblog-penpot-plugin/manifest.json"));
        assertTrue(names.contains("letsblog-penpot-plugin/src/plugin.ts"));
        assertFalse(names.contains("letsblog-penpot-plugin/plugin.js"));
        assertFalse(names.contains("letsblog-penpot-plugin/ui.js"));
        assertTrue(names.stream().noneMatch(n -> n.contains("node_modules")));
    }

    @Test
    void mcpサーバーはnode_modules_logs_coverage_envを含まない() throws IOException {
        SourceArchiveService.SourceArchive archive = service().mcpServer();

        assertEquals("letsblog-mcp-server.zip", archive.filename());
        List<String> names = entries(archive);
        assertTrue(names.contains("letsblog-mcp-server/setup.sh"));
        assertTrue(names.contains("letsblog-mcp-server/package-lock.json"));
        assertTrue(names.contains("letsblog-mcp-server/src/server.js"));
        assertTrue(names.contains("letsblog-mcp-server/.env.example"));
        // penpot側のビルド生成物名(plugin.js)の除外は、mcp-serverのsrc/配下には効かない
        assertTrue(names.contains("letsblog-mcp-server/src/plugin.js"));
        assertFalse(names.contains("letsblog-mcp-server/.env"));
        assertTrue(names.stream().noneMatch(n -> n.contains("node_modules")));
        assertTrue(names.stream().noneMatch(n -> n.contains("logs/")));
        assertTrue(names.stream().noneMatch(n -> n.endsWith(".log")));
        assertTrue(names.stream().noneMatch(n -> n.contains("coverage/")));
    }

    @Test
    void ソースのディレクトリが無ければ明確なエラーにする() {
        SourceArchiveService service = new SourceArchiveService(
                tempDir.resolve("missing-a").toString(), tempDir.resolve("missing-b").toString());

        IllegalStateException e = assertThrows(IllegalStateException.class, service::penpotPlugin);
        assertTrue(e.getMessage().contains("missing-a"));
        assertThrows(IllegalStateException.class, service::mcpServer);
    }

    @Test
    void 読み取り失敗はIllegalStateExceptionにする() throws IOException {
        Path file = tempDir.resolve("a-file");
        Files.writeString(file, "x");
        // ディレクトリではなくファイルを指すので、走査できない
        SourceArchiveService service = new SourceArchiveService(file.toString(), file.toString());
        assertThrows(IllegalStateException.class, service::penpotPlugin);
    }
}
