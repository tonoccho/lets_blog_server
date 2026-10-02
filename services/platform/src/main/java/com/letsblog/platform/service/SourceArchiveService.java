package com.letsblog.platform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Penpotプラグイン / MCPサーバーを「ソース一式のZip」として固める(issue #1491)。
 *
 * <p>VSCode拡張({@link VscodeExtensionBuildService})と違いサーバー側ではビルドしない。
 * 読み取り専用でマウントされたソースを固めて渡すだけで、ビルドは利用者が展開して
 * {@code setup.sh}を実行したときに手元で走る。そのため{@code node_modules}と既存の
 * ビルド生成物は含めず、{@code npm ci}に要る{@code package-lock.json}は含める。
 */
@Service
public class SourceArchiveService {

    /** 配布物のソースではない生成物・実行時の置き場。どの階層にあっても含めない。 */
    private static final Set<String> EXCLUDED_DIRECTORIES =
            Set.of("node_modules", "logs", "coverage", "dist", "build", ".git", ".mocha");

    /** 配布物の直下にあるビルド生成物(.gitignore済みだがマウント元には残りうる)。 */
    private static final Set<String> PENPOT_PLUGIN_BUILD_OUTPUTS = Set.of("plugin.js", "ui.js");

    private static final String PENPOT_PLUGIN_NAME = "letsblog-penpot-plugin";
    private static final String MCP_SERVER_NAME = "letsblog-mcp-server";

    private final Path penpotPluginSource;
    private final Path mcpServerSource;

    public SourceArchiveService(
            @Value("${app.penpot-plugin-source-path}") String penpotPluginSourcePath,
            @Value("${app.mcp-server-source-path}") String mcpServerSourcePath) {
        this.penpotPluginSource = Path.of(penpotPluginSourcePath);
        this.mcpServerSource = Path.of(mcpServerSourcePath);
    }

    /** Zipの本体とダウンロード時のファイル名。 */
    public record SourceArchive(byte[] bytes, String filename) {
    }

    public SourceArchive penpotPlugin() {
        return archive(penpotPluginSource, PENPOT_PLUGIN_NAME, PENPOT_PLUGIN_BUILD_OUTPUTS);
    }

    public SourceArchive mcpServer() {
        return archive(mcpServerSource, MCP_SERVER_NAME, Set.of());
    }

    private SourceArchive archive(Path source, String name, Set<String> excludedRootFiles) {
        if (!Files.isDirectory(source)) {
            throw new IllegalStateException("配布物のソースが見つかりません: " + source);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(source)) {
            files = walk
                    .filter(Files::isRegularFile)
                    .filter(file -> isIncluded(source.relativize(file), excludedRootFiles))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("配布物のソースを読み取れません: " + source, e);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Path file : files) {
                zip.putNextEntry(new ZipEntry(name + "/" + toZipPath(source.relativize(file))));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new SourceArchive(buffer.toByteArray(), name + ".zip");
    }

    private static boolean isIncluded(Path relative, Set<String> excludedRootFiles) {
        for (Path part : relative) {
            if (EXCLUDED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        String fileName = relative.getFileName().toString();
        if (fileName.equals(".env") || fileName.endsWith(".log")) {
            return false;
        }
        return !(relative.getNameCount() == 1 && excludedRootFiles.contains(fileName));
    }

    private static String toZipPath(Path relative) {
        StringBuilder sb = new StringBuilder();
        for (Path part : relative) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
