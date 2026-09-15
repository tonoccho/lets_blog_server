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
 * 各サービスの{@code application.yml}が、ログ行に相関ID({@code %X{correlationId}}、
 * {@link CorrelationIdFilter}がMDCへ載せる値)を出力する{@code logging.pattern.console}を
 * 設定しているかを検証するコントラクトテスト(issue #992)。
 *
 * <p>project-service / publishing-serviceにこの設定が欠けており、障害時に相関IDで
 * ログを串刺しできなかった。設定漏れを次に繰り返さないよう、新しいサービスが増えたときにも
 * 自動的に検知できるようにする。
 *
 * <p>gatewayは対象外: {@code CorrelationIdWebFilter}はリアクティブ実装でMDCを使わず
 * (services/gateway/src/main/java/com/letsblog/gateway/config/CorrelationIdWebFilter.java
 * のJavadoc参照)、相関IDをログメッセージの引数として直接渡すため、この契約は当てはまらない。
 */
class CorrelationIdLoggingPatternContractTest {

    private static final List<String> MDC_BASED_SERVICES = List.of(
            "log-writer", "identity", "media", "ai", "content", "analytics",
            "project", "platform", "publishing");

    @TestFactory
    Stream<DynamicTest> 各サービスのapplication_ymlに相関IDを含むloggingパターンが設定されている() {
        Path repoRoot = findRepoRoot();
        return MDC_BASED_SERVICES.stream()
                .map(service -> dynamicTest(service, () -> {
                    Path ymlPath = repoRoot.resolve("services").resolve(service)
                            .resolve("src/main/resources/application.yml");
                    assertTrue(Files.exists(ymlPath), ymlPath + " が見つかりません");
                    String content = readFile(ymlPath);
                    assertTrue(content.contains("%X{correlationId}"),
                            ymlPath + " の logging.pattern.console に %X{correlationId} が"
                                    + "含まれていません。相関IDでこのサービスのログを串刺しできなくなります。");
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
        throw new IllegalStateException(
                "settings.gradleが見つからずリポジトリルートを特定できませんでした(起点: "
                        + Paths.get("").toAbsolutePath() + ")");
    }
}
