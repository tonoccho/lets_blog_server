package com.letsblog.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1480: マネージドWordPressサイトの自動構築({@code POST /api/sites/managed-wordpress})は
 * 実測で最大240秒かかるが、{@code /api/sites/**}は{@code response-timeout}を持たず
 * 既定の60秒で504になっていた。専用ルートの存在・長さ・nginxとの整合・他経路が不変であることを固定する。
 */
@DisplayName("gateway: サイト自動構築ルートのresponse-timeout(issue #1480)")
class SiteProvisioningRouteTimeoutTest {

    private static final Duration MEASURED_WORST_CASE = Duration.ofSeconds(240);

    private static RouteProperties load() throws IOException {
        Path ymlPath = findRepoRoot().resolve("services/gateway/src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(ymlPath), () -> "application.ymlが見つかりません: " + ymlPath);
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new FileSystemResource(ymlPath));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("app.gateway", RouteProperties.class)
                .orElseThrow(() -> new IllegalStateException("app.gatewayの束縛に失敗しました"));
    }

    private static RouteProperties.Route route(RouteProperties p, String id) {
        return p.getRoutes().stream().filter(r -> id.equals(r.getId())).findFirst().orElse(null);
    }

    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("settings.gradleが見つかりません");
    }

    @Test
    @DisplayName("構築の専用ルートは実測最大240秒を待ちきり、汎用のprojectルートより先に評価される")
    void 構築ルートは実測を待ちきる() throws IOException {
        RouteProperties p = load();
        RouteProperties.Route site = route(p, "project-site-managed-wordpress");

        assertNotNull(site, "project-site-managed-wordpressルートがありません");
        assertEquals(List.of("/api/sites/managed-wordpress"), site.getPaths());
        assertNotNull(site.getResponseTimeout(), "response-timeoutがありません");
        assertTrue(site.getResponseTimeout().compareTo(MEASURED_WORST_CASE) > 0,
                "response-timeout(" + site.getResponseTimeout() + ")が実測最大(240秒)以下");

        int siteIdx = p.getRoutes().indexOf(site);
        int projectIdx = p.getRoutes().indexOf(route(p, "project"));
        assertTrue(siteIdx < projectIdx, "専用ルートがprojectルートより後にあり、先勝ちで効かない");
    }

    @Test
    @DisplayName("projectルートと既定値は従来どおり60秒のまま(短い操作を巻き込まない)")
    void 既定値と汎用ルートは不変() throws IOException {
        RouteProperties p = load();
        RouteProperties.Route project = route(p, "project");

        assertNotNull(project);
        assertNull(project.getResponseTimeout(), "projectルートにresponse-timeoutが付いている");
        assertTrue(project.getPaths().contains("/api/sites/**"));
        assertEquals(Duration.ofSeconds(60), p.getDefaultResponseTimeout());
    }

    @Test
    @DisplayName("nginxの /api/ のproxy_read_timeoutはgatewayのresponse-timeout以上")
    void nginxはgatewayを頭打ちにしない() throws IOException {
        RouteProperties.Route site = route(load(), "project-site-managed-wordpress");
        assertNotNull(site, "project-site-managed-wordpressルートがありません");

        Path conf = findRepoRoot().resolve("infra/nginx/conf.d/default.conf");
        String text = new String(Files.readAllBytes(conf), StandardCharsets.UTF_8);
        Matcher loc = Pattern.compile("location\\s+/api/\\s*\\{").matcher(text);
        assertTrue(loc.find(), "nginxに location /api/ がありません");
        Matcher t = Pattern.compile("proxy_read_timeout\\s+([0-9]+)s\\s*;").matcher(text.substring(loc.end()));
        assertTrue(t.find(), "location /api/ にproxy_read_timeoutがありません");
        Duration nginx = Duration.ofSeconds(Long.parseLong(t.group(1)));

        assertTrue(nginx.compareTo(site.getResponseTimeout()) >= 0,
                "nginx(" + nginx + ")がgateway(" + site.getResponseTimeout() + ")より短く、設定が効かない");
    }

    @Test
    @DisplayName("application.ymlのコメントに対象経路と実測秒数の根拠がある")
    void 根拠コメントがある() throws IOException {
        String yml = new String(Files.readAllBytes(
                findRepoRoot().resolve("services/gateway/src/main/resources/application.yml")),
                StandardCharsets.UTF_8);
        int i = yml.indexOf("id: project-site-managed-wordpress");
        assertTrue(i >= 0, "ルートがありません");
        String before = yml.substring(Math.max(0, i - 1500), i);
        assertTrue(before.contains("#1480") && before.contains("240秒"),
                "直前のコメントに#1480と実測240秒の根拠がありません");
    }
}
