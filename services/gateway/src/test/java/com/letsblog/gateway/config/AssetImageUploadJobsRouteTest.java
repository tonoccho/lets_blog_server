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
 * issue #1764: アセット画像の全環境アップロードの非同期受理口の明示ルート。受理はジョブを1件作って即座に返すだけ
 * (画像本体はgatewayを通らない)なので、長い {@code response-timeout} は与えず既定値を使い、アップロード枠にも
 * 入れない。{@code project-asset-images}({@code /api/projects/*}{@code /asset-images/**})より前に置く(先勝ち)。
 */
@DisplayName("gateway: アセット画像アップロードのジョブ受理口のルート(issue #1764)")
class AssetImageUploadJobsRouteTest {

    private static final String PATH = "/api/projects/*/asset-images/*/upload/jobs";

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
    void ジョブ受理口はpublishingへ向きproject_asset_imagesより前にあり長いタイムアウトを持たない() throws IOException {
        List<RouteProperties.Route> routes = routes();
        int jobs = -1;
        int generic = -1;
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).getPaths().contains(PATH)) {
                jobs = i;
            }
            if ("project-asset-images".equals(routes.get(i).getId())) {
                generic = i;
            }
        }
        assertTrue(jobs >= 0, PATH + " のルートがありません");
        assertTrue(generic >= 0, "汎用project-asset-imagesルートが見つかりません");
        assertTrue(jobs < generic, "ジョブ受理口のルートが汎用ルートより後ろにあります");
        RouteProperties.Route route = routes.get(jobs);
        assertNotNull(route.getUri());
        assertTrue(route.getUri().contains("publishing"), "publishing-serviceへ向いていません: " + route.getUri());
        assertNull(route.getResponseTimeout(), "受理口に長いresponse-timeoutを与えてはいけません");
    }
}
