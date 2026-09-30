package com.letsblog.gateway.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * issue #1337: 記事レビュー({@code /api/projects/{id}/article-review/**})は publishing-service が持つ。
 * 専用ルートが無いと、先勝ちで {@code /api/projects/**}(project-service)にマッチして404になる
 * (issue #642 の contract test が検出した「到達不能エンドポイント」と同型)。
 */
@DisplayName("gateway: 記事レビューのルート(issue #1337)")
class ArticleReviewRouteTest {

    private static List<RouteProperties.Route> routes() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        Path yml = dir.resolve("services/gateway/src/main/resources/application.yml");
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new FileSystemResource(yml));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("app.gateway", RouteProperties.class)
                .orElseThrow(() -> new IllegalStateException("app.gatewayの束縛に失敗しました"))
                .getRoutes();
    }

    @Test
    void article_reviewはpublishingへ向き汎用projectルートより前にある() throws IOException {
        List<RouteProperties.Route> routes = routes();
        int review = -1;
        int generic = -1;
        for (int i = 0; i < routes.size(); i++) {
            if ("project-article-review".equals(routes.get(i).getId())) {
                review = i;
            }
            if ("project".equals(routes.get(i).getId())) {
                generic = i;
            }
        }
        assertTrue(review >= 0, "project-article-review ルートがありません");
        assertTrue(generic >= 0, "汎用projectルートが見つかりません");
        assertTrue(review < generic, "project-article-review が汎用projectルートより後ろにあります");
        RouteProperties.Route route = routes.get(review);
        assertTrue(route.getPaths().contains("/api/projects/*/article-review/**"), "パスが違います");
        assertNotNull(route.getUri());
        assertTrue(route.getUri().contains("PUBLISHING_SERVICE_URI"), "publishingへ向いていません: " + route.getUri());
    }
}
