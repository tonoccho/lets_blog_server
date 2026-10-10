package com.letsblog.common.scheduling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * {@code @EnableScheduling}を持つ全サービスが、タイマー起動の処理に処理IDを採番する仕組み
 * ({@link ScheduledTaskCorrelationConfigurer})を{@code @Bean}として登録していることを検査する
 * (issue #1733)。{@code CorrelationIdFilterRegistrationContractTest}(#992)と同じ形で、
 * 登録漏れ(新しくスケジューリングを有効にしたサービスの付け忘れ)を検知する。
 *
 * <p>対象は{@code @EnableScheduling}のソース上の有無で決める。サービスの一覧を固定で持たないので、
 * 後からスケジューリングを有効にしたサービスも自動的に検査に入る。
 */
class ScheduledTaskCorrelationRegistrationContractTest {

    private static final List<String> ALL_SERVICES = List.of(
            "log-writer", "identity", "media", "ai", "content", "analytics",
            "project", "platform", "publishing", "gateway");

    @Test
    void 検査対象のサービスが1つ以上ある() throws IOException {
        assertFalse(schedulingServices().isEmpty(), "@EnableSchedulingを持つサービスが見つかりません");
    }

    @TestFactory
    Stream<DynamicTest> EnableSchedulingを持つ全サービスが仕組みを登録している() throws IOException {
        Path repoRoot = findRepoRoot();
        return schedulingServices().stream().map(service -> dynamicTest(service, () -> {
            Path sourceRoot = repoRoot.resolve("services").resolve(service).resolve("src/main/java");
            assertTrue(anySourceContains(sourceRoot, "new ScheduledTaskCorrelationConfigurer("),
                    "services/" + service + " は@EnableSchedulingを持つが、new ScheduledTaskCorrelationConfigurer()"
                            + "を@Beanとして登録するクラスが無く、タイマー起動の処理に処理IDが付きません。");
        }));
    }

    private static List<String> schedulingServices() throws IOException {
        Path repoRoot = findRepoRoot();
        return ALL_SERVICES.stream().filter(service -> {
            Path sourceRoot = repoRoot.resolve("services").resolve(service).resolve("src/main/java");
            return Files.exists(sourceRoot) && anySourceContains(sourceRoot, "\n@EnableScheduling");
        }).toList();
    }

    private static boolean anySourceContains(Path sourceRoot, String needle) {
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .anyMatch(p -> readFile(p).contains(needle));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        throw new IllegalStateException("settings.gradleが見つからずリポジトリルートを特定できませんでした");
    }
}
