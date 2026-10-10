package com.letsblog.common.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部宛てHTTP呼び出しの記録({@link ExternalCallLoggingInterceptor})の配線を、ソースツリーの走査で
 * 検証する契約テスト(issue #1734)。{@code RestClient.builder()}を直接呼ぶ箇所は許可リストに
 * 載せ、インターセプタを付けなければならない。載っていない新しい箇所は失敗する。
 * 許可リストの一覧はdocs/LOGGING_AND_MONITORING.mdにも書く。
 */
class ExternalCallLoggingWiringContractTest {

    /** @param builders 該当ファイルの{@code RestClient.builder()}の数 @param targets 付けるべきtarget名(builders個ぶん) */
    record Site(int builders, List<String> targets) {
    }

    /** 注入されたBuilderをcloneして外部へ呼ぶため{@code RestClient.builder()}は無いが、配線が必要なファイル。 */
    static final Map<String, Site> ALLOWLIST = new TreeMap<>();

    static {
        add("services/ai/src/main/java/com/letsblog/ai/ai/BraveSearchClient.java", "brave-search");
        add("services/ai/src/main/java/com/letsblog/ai/service/AiConnectionService.java", "llm-connection-test");
        add("services/ai/src/main/java/com/letsblog/ai/ai/LlmClient.java", "llm", "llm");
        // 共有Builder Bean(ai-serviceの内部クライアントにも注入される)なのでここでは付けず、GithubClientが付ける
        ALLOWLIST.put("services/ai/src/main/java/com/letsblog/ai/config/GithubClientConfig.java",
                new Site(1, List.of()));
        add("services/analytics/src/main/java/com/letsblog/analytics/analytics/GoogleAnalyticsClient.java",
                "google-analytics");
        add("services/analytics/src/main/java/com/letsblog/analytics/adsense/AdSenseClient.java", "google-adsense");
        add("services/identity/src/main/java/com/letsblog/identity/keycloak/KeycloakAdminClientConfig.java",
                "keycloak-admin");
        add("services/identity/src/main/java/com/letsblog/identity/config/ServiceTokenClientConfig.java",
                "keycloak-token");
        add("services/media/src/main/java/com/letsblog/media/ai/ChatGptImageClient.java", "openai-image");
        add("services/media/src/main/java/com/letsblog/media/ai/ComfyUiClient.java", "comfyui");
        add("services/media/src/main/java/com/letsblog/media/ai/PenpotClient.java", "penpot");
        add("services/media/src/main/java/com/letsblog/media/render/PlantUmlClient.java", "plantuml");
        add("services/platform/src/main/java/com/letsblog/platform/keycloak/KeycloakAdminClientConfig.java",
                "keycloak-admin");
        add("services/platform/src/main/java/com/letsblog/platform/service/HttpComputeDeviceHealthProbe.java",
                "compute-device-health");
        add("services/platform/src/main/java/com/letsblog/platform/service/RestDockerEngineClient.java",
                "docker-engine");
        add("services/platform/src/main/java/com/letsblog/platform/service/ContainerStatusService.java",
                "container-status");
        add("services/platform/src/main/java/com/letsblog/platform/service/RabbitMqQueueStatusService.java",
                "rabbitmq-management");
        add("services/platform/src/main/java/com/letsblog/platform/service/LetsBlogServiceStatusService.java",
                "gateway-status");
        add("services/platform/src/main/java/com/letsblog/platform/service/ConnectedServiceStatusService.java",
                "connected-service-status");
        add("services/project/src/main/java/com/letsblog/project/provisioning/AgentRestClients.java",
                "wordpress-agent");
        add("services/project/src/main/java/com/letsblog/project/client/XApiClient.java", "x-api");
        add("services/project/src/main/java/com/letsblog/project/client/LinkedinApiClient.java", "linkedin-api");
        add("services/project/src/main/java/com/letsblog/project/client/FacebookApiClient.java", "facebook-api");
        add("services/project/src/main/java/com/letsblog/project/client/HatenaApiClient.java", "hatena-api");
        add("services/project/src/main/java/com/letsblog/project/client/ThreadsApiClient.java", "threads-api");
        add("services/publishing/src/main/java/com/letsblog/publishing/provisioning/WordPressBulkManagementClient.java",
                "wordpress-bulk");
        add("packages/lbs-common/src/main/java/com/letsblog/common/auth/ServiceTokenClientConfig.java",
                "keycloak-token");
        // 注入Builderをcloneして外部へ呼ぶクライアント(RestClient.builder()は無い)
        ALLOWLIST.put("services/ai/src/main/java/com/letsblog/ai/github/GithubClient.java",
                new Site(0, List.of("github")));
        ALLOWLIST.put("services/publishing/src/main/java/com/letsblog/publishing/github/GithubPullRequestClient.java",
                new Site(0, List.of("github")));
        ALLOWLIST.put("services/publishing/src/main/java/com/letsblog/publishing/cms/agent/WordPressAgentOperations.java",
                new Site(0, List.of("wordpress-agent")));
    }

    private static void add(String file, String... targets) {
        ALLOWLIST.put(file, new Site(targets.length, List.of(targets)));
    }

    private static final Pattern DIRECT_BUILDER = Pattern.compile("RestClient\\.builder\\(\\)");
    private static final Pattern WIRING = Pattern.compile("new ExternalCallLoggingInterceptor\\(\\s*\"([^\"]+)\"");

    /** 契約違反のメッセージを返す。空なら適合。sourcesは「リポジトリ相対パス → ソース本文」。 */
    static List<String> violations(Map<String, String> sources, Map<String, Site> allowlist) {
        List<String> problems = new ArrayList<>();
        sources.forEach((file, text) -> {
            String code = stripComments(text);
            int builders = count(DIRECT_BUILDER, code);
            Site site = allowlist.get(file);
            if (site == null) {
                if (builders > 0) {
                    problems.add(file + " は RestClient.builder() を直接使っていますが許可リストに無く、外部呼び出しが"
                            + "ログに出ません。.requestInterceptor(new ExternalCallLoggingInterceptor(\"<target>\")) を付け、"
                            + "このテストの ALLOWLIST と docs/LOGGING_AND_MONITORING.md の一覧に追加してください。"
                            + "(内部サービス宛てなら SyncServiceClient を使ってください)");
                }
                return;
            }
            if (builders != site.builders()) {
                problems.add(file + " の RestClient.builder() は " + builders + " 箇所ですが許可リストは "
                        + site.builders() + " 箇所です。許可リストを更新してください。");
            }
            List<String> wired = wiredTargets(code);
            for (String target : site.targets()) {
                if (!wired.remove(target)) {
                    problems.add(file + " に target=\"" + target + "\" の ExternalCallLoggingInterceptor の配線がありません。");
                }
            }
        });
        allowlist.keySet().forEach(file -> {
            if (!sources.containsKey(file)) {
                problems.add("許可リストの " + file + " が存在しません。削除または移動されたなら許可リストも更新してください。");
            }
        });
        return problems;
    }

    private static List<String> wiredTargets(String code) {
        List<String> targets = new ArrayList<>();
        Matcher m = WIRING.matcher(code);
        while (m.find()) {
            targets.add(m.group(1));
        }
        return targets;
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String stripComments(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    void リポジトリのRestClient_builder呼び出しが許可リストと配線に適合している() {
        Path root = findRepoRoot();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String module : List.of("services", "packages")) {
            Path modules = root.resolve(module);
            try (Stream<Path> children = Files.list(modules)) {
                children.forEach(child -> collect(root, child.resolve("src/main/java"), sources));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertTrue(sources.size() > 100, "走査対象が少なすぎます(" + sources.size() + ")。パスが変わりましたか");

        List<String> problems = violations(sources, ALLOWLIST);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void 許可リストに無い新しい箇所は失敗する() {
        List<String> problems = violations(
                Map.of("services/x/src/main/java/A.java", "class A { Object c = RestClient.builder().build(); }"),
                Map.of());

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("許可リストに無く"));
    }

    @Test
    void 配線の無い許可リスト箇所は失敗する() {
        List<String> problems = violations(
                Map.of("A.java", "class A { Object c = RestClient.builder().build(); }"),
                Map.of("A.java", new Site(1, List.of("t"))));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("配線がありません"));
    }

    @Test
    void 箇所数がずれたら失敗する() {
        String twice = "RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor(\"t\"));"
                + "RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor(\"t\"));";
        List<String> problems = violations(Map.of("A.java", twice), Map.of("A.java", new Site(1, List.of("t"))));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("2 箇所"));
    }

    @Test
    void 存在しない許可リスト項目は失敗する() {
        List<String> problems = violations(Map.of(), Map.of("Gone.java", new Site(1, List.of("t"))));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("存在しません"));
    }

    @Test
    void 適合していれば違反なしでコメント内の言及は数えない() {
        String ok = "// RestClient.builder() in comment\n"
                + "RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor(\"t\", 1L));";
        assertTrue(violations(Map.of("A.java", ok, "B.java", "/* RestClient.builder() */\n * RestClient.builder()"),
                Map.of("A.java", new Site(1, List.of("t")))).isEmpty());
    }

    private static void collect(Path root, Path sourceRoot, Map<String, String> sources) {
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> sources.put(root.relativize(p).toString().replace('\\', '/'), read(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
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
