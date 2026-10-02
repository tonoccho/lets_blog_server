package com.letsblog.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.AntPathMatcher;

/**
 * issue #1410: 同期のLLM呼び出しを伴うパス(カスタムタグ生成・静的コンテンツ生成・タグデザイン生成)は、
 * 下流のai-serviceが{@code LLM_REQUEST_TIMEOUT_SECONDS}(既定120秒)まで待つのに、gatewayの既定60秒で
 * 先に504になっていた。生成パスだけに専用ルートを切り、同じプレフィックスのCRUDは60秒のまま残す。
 */
@DisplayName("gateway: LLM生成を伴うルートのresponse-timeout(issue #1410)")
class LlmGenerationRouteTimeoutTest {

    private static final Duration DOWNSTREAM_LLM_TIMEOUT = Duration.ofSeconds(120);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static Path root() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        return dir;
    }

    private static List<RouteProperties.Route> routes() throws IOException {
        Path yml = root().resolve("services/gateway/src/main/resources/application.yml");
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new FileSystemResource(yml));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("app.gateway", RouteProperties.class)
                .orElseThrow(() -> new IllegalStateException("app.gatewayの束縛に失敗しました"))
                .getRoutes();
    }

    /** ProxyHandlerと同じく、定義順で最初にマッチしたルートを返す。 */
    private static RouteProperties.Route firstMatch(String path) throws IOException {
        for (RouteProperties.Route r : routes()) {
            for (String pattern : r.getPaths()) {
                if (MATCHER.match(pattern, path)) {
                    return r;
                }
            }
        }
        return null;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/custom-tags/generate",
            "/api/sites/42/static-content/generate",
            "/api/projects/7/tag-design-settings/CALLOUT/generate",
            "/api/tag-design-settings/CALLOUT/generate"
    })
    @DisplayName("LLM生成パスは下流のLLMタイムアウト(120秒)より長いresponse-timeoutに解決される")
    void 生成パスは下流より長く待つ(String path) throws IOException {
        RouteProperties.Route r = firstMatch(path);
        assertNotNull(r, path + " にマッチするルートがありません");
        assertNotNull(r.getResponseTimeout(), path + " は" + r.getId() + "にマッチし、response-timeoutが無い(既定60秒)");
        assertTrue(r.getResponseTimeout().compareTo(DOWNSTREAM_LLM_TIMEOUT) > 0,
                path + " のresponse-timeout(" + r.getResponseTimeout() + ")が下流の120秒以下");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/custom-tags",
            "/api/custom-tags/5",
            "/api/custom-tags/validate",
            "/api/sites",
            "/api/sites/42",
            "/api/sites/42/static-content",
            "/api/projects/7",
            "/api/projects/7/tag-design-settings",
            "/api/projects/7/tag-design-settings/CALLOUT",
            "/api/tag-design-settings",
            "/api/tag-design-settings/CALLOUT"
    })
    @DisplayName("生成を含まないCRUDパスはresponse-timeoutを持たない(既定60秒のまま)")
    void 非生成パスは延びない(String path) throws IOException {
        RouteProperties.Route r = firstMatch(path);
        assertNotNull(r, path + " にマッチするルートがありません");
        assertNull(r.getResponseTimeout(), path + " が" + r.getId() + "にマッチし、不必要に延びている");
    }

    @Test
    @DisplayName("既定値は60秒のまま、広いproject/contentルートも無指定のまま")
    void 既定値と広いルートは不変() throws IOException {
        RouteProperties p = new Binder(ConfigurationPropertySources.from(new YamlPropertySourceLoader().load(
                "application", new FileSystemResource(
                        root().resolve("services/gateway/src/main/resources/application.yml")))))
                .bind("app.gateway", RouteProperties.class).orElseThrow(IllegalStateException::new);
        assertEquals(Duration.ofSeconds(60), p.getDefaultResponseTimeout());
        for (String id : List.of("project", "content", "project-tag-design-settings",
                "project-global-tag-design-settings")) {
            RouteProperties.Route r = p.getRoutes().stream().filter(x -> id.equals(x.getId()))
                    .findFirst().orElse(null);
            assertNotNull(r, id + " ルートがありません");
            assertNull(r.getResponseTimeout(), id + " にresponse-timeoutが付いている");
        }
    }

    @Test
    @DisplayName("nginxの /api/ のproxy_read_timeoutは生成ルートのresponse-timeout以上")
    void nginxは頭打ちにしない() throws IOException {
        Duration max = firstMatch("/api/custom-tags/generate").getResponseTimeout();
        assertNotNull(max, "生成ルートにresponse-timeoutがありません");
        String text = new String(Files.readAllBytes(root().resolve("infra/nginx/conf.d/default.conf")),
                StandardCharsets.UTF_8);
        java.util.regex.Matcher loc = java.util.regex.Pattern.compile("location\\s+/api/\\s*\\{").matcher(text);
        assertTrue(loc.find(), "nginxに location /api/ がありません");
        java.util.regex.Matcher t = java.util.regex.Pattern.compile("proxy_read_timeout\\s+([0-9]+)s\\s*;")
                .matcher(text.substring(loc.end()));
        assertTrue(t.find(), "proxy_read_timeoutがありません");
        assertTrue(Duration.ofSeconds(Long.parseLong(t.group(1))).compareTo(max) >= 0, "nginxが頭打ちにする");
    }
}
