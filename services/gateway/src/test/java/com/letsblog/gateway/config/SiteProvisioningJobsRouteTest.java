package com.letsblog.gateway.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1479: サイト自動構築の非同期受理口 {@code POST /api/sites/managed-wordpress/jobs} の明示ルート。
 * 受理はジョブを1件作って即座に返すだけなので、長い {@code response-timeout} は与えず既定値を使う
 * (#1405の {@code ai-image-jobs} と同じ)。{@code /api/sites/**} の汎用ルートより前に置く(先勝ち)。
 */
@DisplayName("gateway: サイト自動構築ジョブ受理口のルート(issue #1479)")
class SiteProvisioningJobsRouteTest {

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

    @Test
    void ジョブ受理口はprojectへ向き汎用ルートより前にあり長いタイムアウトを持たない() throws IOException {
        List<RouteProperties.Route> routes = routes();
        int jobs = -1;
        int generic = -1;
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).getPaths().contains("/api/sites/managed-wordpress/jobs")) {
                jobs = i;
            }
            if ("project".equals(routes.get(i).getId())) {
                generic = i;
            }
        }
        assertTrue(jobs >= 0, "/api/sites/managed-wordpress/jobs のルートがありません");
        assertTrue(generic >= 0, "汎用projectルートが見つかりません");
        assertTrue(jobs < generic, "ジョブ受理口のルートが汎用projectルートより後ろにあります");
        RouteProperties.Route route = routes.get(jobs);
        assertNotNull(route.getUri());
        assertTrue(route.getUri().contains("project"), "project-serviceへ向いていません: " + route.getUri());
        assertNull(route.getResponseTimeout(), "受理口に長いresponse-timeoutを与えてはいけません");
    }
}
