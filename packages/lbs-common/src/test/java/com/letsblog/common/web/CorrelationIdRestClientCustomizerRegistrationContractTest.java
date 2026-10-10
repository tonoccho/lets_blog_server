package com.letsblog.common.web;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * 各サービスが{@link CorrelationIdRestClientCustomizer}を{@code @Bean}として登録しているかを
 * ソースツリーの走査で検証する契約テスト(issue #1730)。登録が漏れたサービスでは、注入された
 * RestClient.Builderから作るクライアントが処理IDを送らず、呼び出し先で追跡が途切れる。
 */
class CorrelationIdRestClientCustomizerRegistrationContractTest {

    private static final List<String> SERVICES = List.of(
            "log-writer", "identity", "media", "ai", "content", "analytics",
            "project", "platform", "publishing");

    @TestFactory
    Stream<DynamicTest> 各サービスがCorrelationIdRestClientCustomizerをBeanとして登録している() {
        Path repoRoot = findRepoRoot();
        return SERVICES.stream().map(service -> dynamicTest(service, () -> {
            Path sourceRoot = repoRoot.resolve("services").resolve(service).resolve("src/main/java");
            assertTrue(Files.exists(sourceRoot), sourceRoot + " が見つかりません");
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                assertTrue(files.filter(p -> p.toString().endsWith(".java"))
                                .anyMatch(p -> readFile(p).contains("new CorrelationIdRestClientCustomizer()")),
                        "services/" + service + " に new CorrelationIdRestClientCustomizer() を@Beanとして"
                                + "登録するクラスが無く、RestClient経由の呼び出しで処理IDが途切れます。");
            }
        }));
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("settings.gradleが見つかりません(起点: " + Paths.get("").toAbsolutePath() + ")");
    }
}
