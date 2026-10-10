package com.letsblog.common.web;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * 全サービス(gatewayを含む)の{@code application.yml}が、Spring Bootの標準の構造化ログ
 * (1行1JSON、logstash形式)を標準出力に設定しているかを検証するコントラクトテスト(issue #1729)。
 * 旧{@code CorrelationIdLoggingPatternContractTest}(平文パターンに{@code %X{correlationId}}が
 * あるかの検査)を置き換える。
 *
 * <p>logstash形式はMDCの全キーをJSONの項目として出すので、{@link CorrelationIdFilter}が載せる
 * {@code correlationId}は設定なしで各行の項目になる。サービス名はlogstash形式が自動では
 * 付けないため{@code logging.structured.json.add.service}で足す。バナーはJSON行ではないので
 * {@code spring.main.banner-mode=off}で消す。
 *
 * <p>対象は{@code services/}直下のディレクトリすべて。サービスが増えて設定を忘れたときも検知する。
 */
class StructuredLoggingContractTest {

    @TestFactory
    Stream<DynamicTest> 全サービスのapplication_ymlが構造化ログ_logstash形式を標準出力に設定している() {
        Path repoRoot = findRepoRoot();
        return listServices(repoRoot).stream().map(service -> dynamicTest(service, () -> {
            Map<String, String> props = loadProperties(repoRoot, service);
            assertEquals("logstash", props.get("logging.structured.format.console"),
                    "services/" + service + " の application.yml に logging.structured.format.console: "
                            + "logstash が無い。標準出力が1行1JSONにならず、処理IDごとの解析ができない(#1729)。");
        }));
    }

    @TestFactory
    Stream<DynamicTest> 全サービスの構造化ログの各行にサービス名が付く() {
        Path repoRoot = findRepoRoot();
        return listServices(repoRoot).stream().map(service -> dynamicTest(service, () -> {
            Map<String, String> props = loadProperties(repoRoot, service);
            String value = props.get("logging.structured.json.add.service");
            assertTrue(value != null && !value.isBlank(),
                    "services/" + service + " の application.yml に logging.structured.json.add.service が無い。"
                            + "JSON行からどのサービスの行か分からなくなる(#1729)。");
        }));
    }

    @TestFactory
    Stream<DynamicTest> 全サービスがバナーを出さずJSON以外の行を標準出力に混ぜない() {
        Path repoRoot = findRepoRoot();
        return listServices(repoRoot).stream().map(service -> dynamicTest(service, () -> {
            Map<String, String> props = loadProperties(repoRoot, service);
            assertEquals("off", props.get("spring.main.banner-mode"),
                    "services/" + service + " の application.yml に spring.main.banner-mode: off が無い。"
                            + "バナーがJSONでない行として混ざる(#1729)。");
        }));
    }

    @TestFactory
    Stream<DynamicTest> 全サービスが構造化ログと衝突する平文パターンを残していない() {
        Path repoRoot = findRepoRoot();
        return listServices(repoRoot).stream().map(service -> dynamicTest(service, () -> {
            Map<String, String> props = loadProperties(repoRoot, service);
            assertFalse(props.containsKey("logging.pattern.console"),
                    "services/" + service + " の application.yml に logging.pattern.console が残っている。"
                            + "構造化ログと二重の出力設定になる(#1729)。");
        }));
    }

    @TestFactory
    Stream<DynamicTest> 対象のサービスが10件ありgatewayを含む() {
        List<String> services = listServices(findRepoRoot());
        return Stream.of(dynamicTest("services/配下は10サービスでgatewayを含む", () -> {
            assertEquals(10, services.size(), "services/ 配下: " + services);
            assertTrue(services.contains("gateway"));
        }));
    }

    private static List<String> listServices(Path repoRoot) {
        try (Stream<Path> dirs = Files.list(repoRoot.resolve("services"))) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("src/main/resources/application.yml")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * application.yml(複数ドキュメントあり)を、ドット区切りのキー→値に平坦化する。
     * YAMLライブラリを使わず、インデントでキーの入れ子をたどる(ここで見る設定はすべて単純な
     * 「キー: 値」)。コメント行・空行は無視し、ドキュメントの区切り({@code ---})をまたいで統合する。
     */
    static Map<String, String> loadProperties(Path repoRoot, String service) {
        Path yml = repoRoot.resolve("services").resolve(service).resolve("src/main/resources/application.yml");
        Map<String, String> result = new HashMap<>();
        Deque<int[]> indents = new ArrayDeque<>();
        List<String> keys = new ArrayList<>();
        for (String raw : readFile(yml).split("\n", -1)) {
            if (raw.startsWith("---")) {
                indents.clear();
                keys.clear();
                continue;
            }
            String trimmed = raw.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            int indent = raw.length() - raw.stripLeading().length();
            while (!indents.isEmpty() && indents.peek()[0] >= indent) {
                indents.pop();
                keys.remove(keys.size() - 1);
            }
            String key = trimmed.substring(0, colon).strip();
            String value = trimmed.substring(colon + 1).strip();
            indents.push(new int[] {indent});
            keys.add(key);
            if (!value.isEmpty() && !value.startsWith("#")) {
                result.put(String.join(".", keys), unquote(value));
            }
        }
        return result;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
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
