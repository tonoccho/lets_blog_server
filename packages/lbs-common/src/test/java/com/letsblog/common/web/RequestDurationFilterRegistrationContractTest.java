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
 * 各Servletサービスが{@link RequestDurationLoggingFilter}を{@code @Bean}として登録しているかと、
 * 全サービス(gatewayを含む)の{@code application.yml}が{@code http.server.requests}の
 * パーセンタイル(p95/p99)を設定しているかを検証するコントラクトテスト(issue #1470)。
 * {@link CorrelationIdFilterRegistrationContractTest}(#992)と同じ形で、登録漏れを検知する。
 */
class RequestDurationFilterRegistrationContractTest {

    private static final List<String> SERVLET_SERVICES = List.of(
            "log-writer", "identity", "media", "ai", "content", "analytics",
            "project", "platform", "publishing");

    private static final List<String> ALL_SERVICES = Stream
            .concat(SERVLET_SERVICES.stream(), Stream.of("gateway")).toList();

    @TestFactory
    Stream<DynamicTest> 各ServletサービスがRequestDurationLoggingFilterをBeanとして登録している() {
        Path repoRoot = findRepoRoot();
        return SERVLET_SERVICES.stream().map(service -> dynamicTest(service, () -> {
            Path sourceRoot = repoRoot.resolve("services").resolve(service).resolve("src/main/java");
            assertTrue(Files.exists(sourceRoot), sourceRoot + " が見つかりません");
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                assertTrue(files.filter(p -> p.toString().endsWith(".java"))
                                .anyMatch(p -> readFile(p).contains("new RequestDurationLoggingFilter(")),
                        "services/" + service + " に new RequestDurationLoggingFilter(...) を@Beanとして"
                                + "登録するクラスが無く、このサービスの所要時間ログが出ません。");
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> 全サービスのapplication_ymlがhttp_server_requestsのp95_p99を設定している() {
        Path repoRoot = findRepoRoot();
        return ALL_SERVICES.stream().map(service -> dynamicTest(service, () -> {
            Path yml = repoRoot.resolve("services").resolve(service)
                    .resolve("src/main/resources/application.yml");
            assertTrue(Files.exists(yml), yml + " が見つかりません");
            assertTrue(readFile(yml).matches(
                            "(?s).*\\n\\s*percentiles:\\s*\\n\\s*http\\.server\\.requests:\\s*[\"']?0\\.95,\\s*0\\.99[\"']?\\s*\\n.*"),
                    yml + " に management.metrics.distribution.percentiles.http.server.requests: 0.95,0.99 が"
                            + "ありません。percentiles-histogram はActuatorのp95/p99を出さないので代用できません。");
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
