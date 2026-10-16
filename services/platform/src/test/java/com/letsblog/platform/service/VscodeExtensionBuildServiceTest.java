package com.letsblog.platform.service;

import com.letsblog.platform.service.VscodeExtensionBuildService.VscodeExtensionBuildException;
import com.letsblog.platform.testsupport.FakeBin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy-apiのVscodeExtensionBuildServiceTestと同じ観点をplatform-serviceへ移設したもの
 * (issue #696、C10-4)。ソース未検出時のエラーハンドリングと、キャッシュを使わず必ず再ビルドすることを検証する。
 *
 * <p>issue #1825: npm / npx は{@code src/test/fake-bin}の代役の実行ファイルで、{@code VscodeExtensionBuildService}の実物
 * (ProcessBuilderでの起動を含む)がそのまま呼ぶ。サービスをスパイしたり継承して差し替えたりはしない。
 */
class VscodeExtensionBuildServiceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetFakeBin() {
        FakeBin.reset();
    }

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
    void buildAndGetVsix_古いvsixが残っていてもキャッシュせず再ビルドする() throws IOException {
        VscodeExtensionBuildService service = service();
        writePackageJson("1.2.3");
        Path outputDir = buildDir.resolve("output");
        Files.createDirectories(outputDir);
        Path stale = outputDir.resolve("letsblog-vscode-1.2.3.vsix");
        Files.writeString(stale, "stale-dummy-content");

        VscodeExtensionBuildService.BuiltExtension built = service.buildAndGetVsix();

        // もしキャッシュヒットのショートカットが復活していたら、npmは呼ばれず古い内容がそのまま返る。
        assertEquals(List.of(
                List.of("npm", "ci", "--no-audit", "--no-fund"),
                List.of("npm", "run", "compile"),
                List.of("npx", "vsce", "package", "--allow-missing-repository", "--out",
                        built.vsixPath().toString())), FakeBin.calls());
        assertEquals("letsblog-vscode-1.2.3.vsix", built.filename());
        assertEquals("fake vsix\n", Files.readString(built.vsixPath()));
        assertEquals("stale-dummy-content", Files.readString(stale), "古いファイルには触れない");
    }

    @Test
    void buildAndGetVsix_ソースをコピーして_node_modulesとoutは除く() throws IOException {
        VscodeExtensionBuildService service = service();
        writePackageJson("1.0.0");
        Files.createDirectories(sourceDir.resolve("src"));
        Files.writeString(sourceDir.resolve("src/extension.ts"), "export {}");
        Files.createDirectories(sourceDir.resolve("node_modules/dep"));
        Files.writeString(sourceDir.resolve("node_modules/dep/index.js"), "x");
        Files.createDirectories(sourceDir.resolve("out"));
        Files.writeString(sourceDir.resolve("out/extension.js"), "x");

        VscodeExtensionBuildService.BuiltExtension built = service.buildAndGetVsix();

        String copied = FakeBin.npmWorkingDirectories().get(0);
        assertTrue(copied.contains("./package.json"), copied);
        assertTrue(copied.contains("./src/extension.ts"), copied);
        assertFalse(copied.contains("node_modules"), copied);
        assertFalse(copied.contains("out"), copied);
        assertFalse(Files.exists(buildDir.resolve("workspace")), "作業ディレクトリはビルド後に片付ける");
        assertTrue(Files.exists(built.vsixPath()));
    }

    @Test
    void buildAndGetVsix_npmが失敗したら終了コードと出力つきの例外() throws IOException {
        VscodeExtensionBuildService service = service();
        writePackageJson("1.2.3");
        FakeBin.fail("npm", "npm ERR! code E404");

        VscodeExtensionBuildException exception =
                assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);

        assertTrue(exception.getMessage().contains("npm ci --no-audit --no-fund が失敗しました(exit=1)"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("E404"), exception.getMessage());
    }

    @Test
    void buildAndGetVsix_ビルド中に割り込まれたら中断の例外にして割り込み状態を戻す() throws Exception {
        VscodeExtensionBuildService service = service();
        writePackageJson("1.2.3");
        FakeBin.npxSleepSeconds("5");
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                service.buildAndGetVsix();
            } catch (Throwable t) {
                thrown.set(t);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        worker.start();
        // npx が起動する(=待機に入る)まで待ってから割り込む。
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (FakeBin.calls().stream().noneMatch(c -> c.get(0).equals("npx")) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        worker.interrupt();
        worker.join(10_000);

        assertFalse(worker.isAlive());
        assertTrue(thrown.get() instanceof VscodeExtensionBuildException, String.valueOf(thrown.get()));
        assertTrue(interrupted.get(), "割り込み状態を戻してから例外を伝える");
    }

    @Test
    void buildAndGetVsix_package_jsonを読めなければ例外() throws IOException {
        VscodeExtensionBuildService service = service();
        // package.json という名前のディレクトリは存在するが、ファイルとして読めない。
        Files.createDirectories(sourceDir.resolve("package.json"));

        VscodeExtensionBuildException exception =
                assertThrows(VscodeExtensionBuildException.class, service::buildAndGetVsix);

        assertTrue(exception.getMessage().contains("読み込みに失敗"), exception.getMessage());
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

    /**
     * issue #1190: 出力ファイル名がバージョン番号のみに基づく固定パスだったため、
     * 並行リクエストの一方が他方のビルド成果物を`Files.deleteIfExists`で削除し
     * 500(FileNotFoundException)になっていた。リクエストごとに一意な出力先を
     * 割り当てることで、この衝突が起こり得ないことを保証する。
     */
    @Test
    void allocateRequestOutputDir_呼び出しごとに一意なディレクトリを返す() {
        VscodeExtensionBuildService service = service();
        Path outputRoot = buildDir.resolve("output");

        Path first = service.allocateRequestOutputDir(outputRoot);
        Path second = service.allocateRequestOutputDir(outputRoot);

        assertNotEquals(first, second);
        assertEquals(outputRoot, first.getParent());
        assertEquals(outputRoot, second.getParent());
    }

    /**
     * issue #1190: リクエスト専用の出力先ディレクトリはレスポンス送出後に残り続けると
     * ディスクを食い潰すため、ダウンロード完了後に確実に削除できることを検証する。
     */
    @Test
    void cleanupAfterDownload_リクエスト専用の出力先ディレクトリを削除する() throws IOException {
        VscodeExtensionBuildService service = service();
        Path requestOutputDir = buildDir.resolve("output").resolve("req-uuid");
        Files.createDirectories(requestOutputDir);
        Path vsix = requestOutputDir.resolve("letsblog-vscode-1.0.0.vsix");
        Files.writeString(vsix, "dummy vsix content");
        VscodeExtensionBuildService.BuiltExtension built =
                new VscodeExtensionBuildService.BuiltExtension(vsix, "letsblog-vscode-1.0.0.vsix", requestOutputDir);

        service.cleanupAfterDownload(built);

        assertFalse(Files.exists(requestOutputDir));
    }
}
