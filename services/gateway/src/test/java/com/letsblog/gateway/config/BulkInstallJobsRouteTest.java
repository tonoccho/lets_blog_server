package com.letsblog.gateway.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1702: 全環境インストール(slug / zip)の非同期受理口の明示ルート。受理はジョブを1件作って
 * 即座に返すだけなので、長い {@code response-timeout} は与えず既定値を使う(一括管理の
 * {@code project-bulk-management} は300秒)。{@code /api/projects/*}{@code /bulk-management/**} の
 * 汎用ルートより前に置く(先勝ち)。
 */
@DisplayName("gateway: 全環境インストールのジョブ受理口のルート(issue #1702)")
class BulkInstallJobsRouteTest {

    private static List<RouteProperties.Route> routes() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        Path yml = dir.resolve("services/gateway/src/main/resources/application.yml");
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new FileSystemResource(yml));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("app.gateway", RouteProperties.class)
                .orElseThrow(() -> new IllegalStateException("app.gatewayの束縛に失敗しました")).getRoutes();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/projects/*/bulk-management/apply-all/jobs",
            "/api/projects/*/bulk-management/upload/jobs"})
    void ジョブ受理口はpublishingへ向き一括管理の汎用ルートより前にあり長いタイムアウトを持たない(String path) throws IOException {
        List<RouteProperties.Route> routes = routes();
        int jobs = -1;
        int generic = -1;
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).getPaths().contains(path)) {
                jobs = i;
            }
            if ("project-bulk-management".equals(routes.get(i).getId())) {
                generic = i;
            }
        }
        assertTrue(jobs >= 0, path + " のルートがありません");
        assertTrue(generic >= 0, "汎用project-bulk-managementルートが見つかりません");
        assertTrue(jobs < generic, "ジョブ受理口のルートが汎用ルートより後ろにあります");
        RouteProperties.Route route = routes.get(jobs);
        assertNotNull(route.getUri());
        assertTrue(route.getUri().contains("publishing"), "publishing-serviceへ向いていません: " + route.getUri());
        assertNull(route.getResponseTimeout(), "受理口に長いresponse-timeoutを与えてはいけません");
    }
}
