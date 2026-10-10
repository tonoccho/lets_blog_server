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
 * 各サービスが{@link CorrelationIdFilter}を{@code @Bean}として実際に登録しているかを
 * 検証するコントラクトテスト(issue #992)。
 *
 * <p>project-service / publishing-serviceには{@code logging.pattern.console}が欠けていた
 * だけでなく、他7サービスにある{@code CorrelationIdConfig}(このフィルタをservlet filterとして
 * 登録するクラス)自体が存在しなかった。そのためMDCに相関IDが一度も載らず、ログの出力設定を
 * 直すだけでは効果が無かった。フィルタ登録そのものの欠落を次に繰り返さないよう、
 * サービスのソースツリーを走査して検知する。
 *
 * <p>gatewayは対象外: リアクティブ実装でMDCを使わず、相関IDをログの引数で渡すため(構造化ログ側の契約は{@link StructuredLoggingContractTest})。
 */
class CorrelationIdFilterRegistrationContractTest {

    private static final List<String> MDC_BASED_SERVICES = List.of(
            "log-writer", "identity", "media", "ai", "content", "analytics",
            "project", "platform", "publishing");

    @TestFactory
    Stream<DynamicTest> 各サービスがCorrelationIdFilterをBeanとして登録している() {
        Path repoRoot = findRepoRoot();
        return MDC_BASED_SERVICES.stream()
                .map(service -> dynamicTest(service, () -> {
                    Path sourceRoot = repoRoot.resolve("services").resolve(service)
                            .resolve("src/main/java");
                    assertTrue(Files.exists(sourceRoot), sourceRoot + " が見つかりません");
                    assertTrue(scanForFilterRegistration(sourceRoot),
                            "services/" + service + "/src/main/java 配下に new CorrelationIdFilter() "
                                    + "を@Beanとして登録するクラスが見つかりません。MDCに相関IDが載らず、"
                                    + "logging.pattern.consoleを設定しても効果がありません。");
                }));
    }

    private static boolean scanForFilterRegistration(Path sourceRoot) throws IOException {
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files
                    .filter(p -> p.toString().endsWith(".java"))
                    .anyMatch(p -> readFile(p).contains("new CorrelationIdFilter()"));
        }
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
