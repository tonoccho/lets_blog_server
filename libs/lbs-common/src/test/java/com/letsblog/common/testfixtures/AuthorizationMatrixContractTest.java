package com.letsblog.common.testfixtures;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 走査ロジックの単体テスト(issue #833)。
 *
 * <p>#805 の契約テストは実サービスのソースを走査するため、走査ロジック自体の穴は
 * 「現行コードベースに該当する書き方が無い」と再現できない。ここでは合成したソースを
 * 走査させ、解釈できない書き方が<b>無音で捨てられない</b>ことを固定する。
 */
class AuthorizationMatrixContractTest {

    @TempDir
    Path sourceRoot;

    private Path writeController(String body) throws IOException {
        Path dir = sourceRoot.resolve("com/example/controller");
        Files.createDirectories(dir);
        Path file = dir.resolve("ProbeController.java");
        Files.writeString(file, """
                package com.example.controller;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/probe")
                public class ProbeController {
                %s
                }
                """.formatted(body));
        return file;
    }

    @Test
    @DisplayName("配列の要素が定数参照だと落ちる(無音で捨てない)")
    void 配列要素の定数参照は落ちる() throws IOException {
        // 修正前は第1要素のリテラルが取れてリスト全体が非空になるため、
        // 定数要素が警告も出ずに捨てられていた。第1要素が既に一覧にある場合は完全に無音になる。
        writeController("""
                    private static final String K = "/by-const";

                    @GetMapping({"/by-slug", K})
                    public String probe() {
                        return "";
                    }
                """);

        AssertionError error = assertThrows(AssertionError.class,
                () -> AuthorizationMatrixContract.scanControllerEndpoints(sourceRoot));
        assertTrue(error.getMessage().contains("文字列リテラルではありません"), error.getMessage());
        assertTrue(error.getMessage().contains("K"), error.getMessage());
    }

    @Test
    @DisplayName("配列のリテラル2要素は従来どおり両方とも検出される")
    void 配列のリテラル要素は両方検出される() throws IOException {
        writeController("""
                    @GetMapping({"/a", "/b"})
                    public String probe() {
                        return "";
                    }
                """);

        assertEquals(Set.of("/api/probe/a", "/api/probe/b"), pathsOf(sourceRoot));
    }

    @Test
    @DisplayName("単独の定数参照は従来どおり落ちる")
    void 単独の定数参照は落ちる() throws IOException {
        writeController("""
                    private static final String K = "/by-const";

                    @GetMapping(K)
                    public String probe() {
                        return "";
                    }
                """);

        AssertionError error = assertThrows(AssertionError.class,
                () -> AuthorizationMatrixContract.scanControllerEndpoints(sourceRoot));
        assertTrue(error.getMessage().contains("文字列リテラルではありません"), error.getMessage());
    }

    @Test
    @DisplayName("要素内の文字列連結も落ちる")
    void 要素内の連結は落ちる() throws IOException {
        writeController("""
                    private static final String K = "/tail";

                    @GetMapping({"/a" + K})
                    public String probe() {
                        return "";
                    }
                """);

        AssertionError error = assertThrows(AssertionError.class,
                () -> AuthorizationMatrixContract.scanControllerEndpoints(sourceRoot));
        assertTrue(error.getMessage().contains("文字列リテラルではありません"), error.getMessage());
    }

    @Test
    @DisplayName("末尾カンマの空要素は検査対象にしない")
    void 末尾カンマは許容する() throws IOException {
        writeController("""
                    @GetMapping({"/a", "/b",})
                    public String probe() {
                        return "";
                    }
                """);

        assertDoesNotThrow(() -> AuthorizationMatrixContract.scanControllerEndpoints(sourceRoot));
    }

    @Test
    @DisplayName("パス以外の属性が併記されていても要素検査は正しく働く")
    void 属性併記でも検出される() throws IOException {
        writeController("""
                    @GetMapping(value = {"/a", "/b"}, produces = "text/plain")
                    public String probe() {
                        return "";
                    }
                """);

        assertEquals(Set.of("/api/probe/a", "/api/probe/b"), pathsOf(sourceRoot));
    }

    @Test
    @DisplayName("コメント内の文字列は幻のパスとして採らない")
    void コメント内の文字列は採らない() throws IOException {
        writeController("""
                    @GetMapping({
                            // 旧パス: "/old"
                            "/a"})
                    public String probe() {
                        return "";
                    }
                """);

        assertEquals(Set.of("/api/probe/a"), pathsOf(sourceRoot));
    }

    private static Set<String> pathsOf(Path sourceRoot) {
        List<AuthorizationMatrixContract.Endpoint> found =
                AuthorizationMatrixContract.scanControllerEndpoints(sourceRoot);
        return found.stream().map(AuthorizationMatrixContract.Endpoint::path).collect(Collectors.toSet());
    }
}
