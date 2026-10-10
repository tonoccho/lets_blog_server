package com.letsblog.common.db;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * JPAを使う全サービスでDBクエリ記録(issue #1736)が有効なことを検証するコントラクトテスト。
 * JPAを使うサービスは{@code build.gradle}の{@code data-jpa}依存から検出し、固定の一覧と突き合わせる
 * ので、新しくJPAを使うサービスが増えて登録が漏れても落ちる。
 * 有効とは、(1) {@link DbQueryMetricsDataSourcePostProcessor}が{@code static @Bean}で登録され、
 * (2) {@code RequestDurationLoggingFilter}にDB閾値({@code app.db-metrics.*})が渡されていること。
 * どちらが欠けても{@code service request:}行の{@code db_queries=}が常に0になる。
 */
class DbQueryMetricsRegistrationContractTest {

    private static final List<String> JPA_SERVICES = List.of(
            "ai", "analytics", "content", "identity", "log-writer", "media", "platform", "project", "publishing");

    @Test
    void JPAを使うサービスは9つで固定の一覧と一致する() throws IOException {
        Path services = findRepoRoot().resolve("services");
        List<String> detected;
        try (Stream<Path> dirs = Files.list(services)) {
            detected = dirs.filter(d -> Files.exists(d.resolve("build.gradle")))
                    .filter(d -> readFile(d.resolve("build.gradle")).contains("data-jpa"))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        }

        assertEquals(JPA_SERVICES.stream().sorted().toList(), detected,
                "JPAを使うサービスの集合が変わりました。DBクエリ記録の登録(issue #1736)と本テストの一覧を更新してください。");
    }

    @TestFactory
    Stream<DynamicTest> JPAを使う各サービスがDataSourcePostProcessorを静的Beanとして登録している() {
        Path repoRoot = findRepoRoot();
        return JPA_SERVICES.stream().map(service -> dynamicTest(service, () -> {
            String source = allJava(repoRoot, service);
            assertTrue(source.contains("static DbQueryMetricsDataSourcePostProcessor")
                            && source.contains("new DbQueryMetricsDataSourcePostProcessor()"),
                    "services/" + service + " に DbQueryMetricsDataSourcePostProcessor を static @Bean として登録する"
                            + "クラスが無く、このサービスのDBクエリが記録されません。");
        }));
    }

    @TestFactory
    Stream<DynamicTest> JPAを使う各サービスがフィルタへDB閾値の設定を渡している() {
        Path repoRoot = findRepoRoot();
        return JPA_SERVICES.stream().map(service -> dynamicTest(service, () -> {
            String source = allJava(repoRoot, service);
            assertTrue(source.contains("app.db-metrics.slow-query-threshold-ms")
                            && source.contains("app.db-metrics.repeat-threshold")
                            && source.contains("new RequestDurationLoggingFilter(slowThresholdMs, "),
                    "services/" + service + " の RequestDurationLoggingFilter にDB閾値"
                            + "(app.db-metrics.slow-query-threshold-ms / app.db-metrics.repeat-threshold)が渡されていません。");
        }));
    }

    private static String allJava(Path repoRoot, String service) {
        Path sourceRoot = repoRoot.resolve("services").resolve(service).resolve("src/main/java");
        assertTrue(Files.exists(sourceRoot), sourceRoot + " が見つかりません");
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .map(DbQueryMetricsRegistrationContractTest::readFile)
                    .collect(Collectors.joining("\n"));
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
        throw new IllegalStateException("settings.gradleが見つかりません(起点: " + Paths.get("").toAbsolutePath() + ")");
    }
}
