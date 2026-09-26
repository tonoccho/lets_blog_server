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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1405: 画像生成の非同期受理口 {@code POST /api/ai/image/jobs} は media-service が持つ。
 * {@code /api/ai/**} は ai-service 向けなので、明示ルートが無いと ai-service へ流れて404になる。
 * ルートは先勝ちのため、汎用の {@code ai} ルートより前に置かれていることも固定する。
 * 受理は数百ミリ秒で終わるので、生成本体用の長いresponse-timeoutは与えない。
 */
@DisplayName("gateway: 画像生成ジョブ受理口のルート(issue #1405)")
class AiImageJobsRouteTest {

    private static List<RouteProperties.Route> routes() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        Path yml = dir.resolve("services/gateway/src/main/resources/application.yml");
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new FileSystemResource(yml));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("app.gateway", RouteProperties.class).orElseThrow(() -> new IllegalStateException("app.gatewayの束縛に失敗しました")).getRoutes();
    }

    @Test
    void ジョブ受理口はmediaへ向き汎用aiルートより前にある() throws IOException {
        List<RouteProperties.Route> routes = routes();
        int jobs = -1;
        int generic = -1;
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).getPaths().contains("/api/ai/image/jobs")) {
                jobs = i;
            }
            if ("ai".equals(routes.get(i).getId())) {
                generic = i;
            }
        }
        assertTrue(jobs >= 0, "/api/ai/image/jobs のルートがありません");
        assertTrue(generic >= 0, "汎用aiルートが見つかりません");
        assertTrue(jobs < generic, "ジョブ受理口のルートが汎用aiルートより後ろにあります");
        RouteProperties.Route route = routes.get(jobs);
        assertNotNull(route.getUri());
        assertTrue(route.getUri().contains("media"), "media-serviceへ向いていません: " + route.getUri());
    }
}
