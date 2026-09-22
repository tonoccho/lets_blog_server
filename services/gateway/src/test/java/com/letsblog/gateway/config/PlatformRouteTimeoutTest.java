package com.letsblog.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1190 症状B: {@code GET /api/system/vscode-extension}(VSCode拡張のオンデマンドビルド)は
 * {@code platform}ルート({@code /api/system/**})を経由するが、これまで{@code response-timeout}の
 * 上書きが無く{@code default-response-timeout: 60s}のままだった。
 *
 * <p>2026-09-19のリリース検証(develop {@code f261d2bd}、run {@code 20260919T204741Z-2375325})の
 * 実測では、1回のビルド(npm ci → npm run compile → npx vsce package)は
 * <b>47〜53秒</b>かかっている(gatewayのアクセスログ・platformのビルドログから算出。
 * このテストのjavadoc下部に計算根拠を残す)。60秒の既定値はこの1回分だけでも既に
 * ぎりぎりで、2並列が直列に走れば確実に超えて504になっていた。
 *
 * <p>{@code platform}ルートには{@code /api/backup/**}・{@code /api/system-settings/**}も
 * 同居しているため、このテストは値の下限だけを見る(このルート全体を長くしても、
 * 他のエンドポイントが早く返す分には影響しない)。
 *
 * <p>上限はnginx({@code infra/nginx/conf.d/default.conf}の{@code location /api/}、
 * {@code proxy_read_timeout 1200s})。{@code /api/system/**}専用のlocationは無く、この
 * 広い{@code location /api/}がそのまま適用されるため、gateway側の値がこれを超えても
 * 前段のnginxが先に切ってしまい効かない。
 */
@DisplayName("gateway: platformルート(VSCode拡張ビルド)のresponse-timeout(issue #1190 症状B)")
class PlatformRouteTimeoutTest {

    /**
     * 実測(47〜53秒)に対する下限。合流(issue #1190)により後続リクエストの応答時間は
     * 「先行の全ビルド + 自分のビルド」ではなく1回分のビルド時間で済むようになるが、
     * 多少遅い環境(npmキャッシュが冷えている等)でも504にならないよう、実測の最大値
     * 53秒に3倍弱の余裕を持たせた値を最低ラインとする。既存の重いルート(ai系180秒)と
     * 同じ桁の余裕の持たせ方。
     */
    private static final Duration MEASURED_WORST_CASE_FLOOR = Duration.ofSeconds(150);

    /** 前段nginxの{@code location /api/}の{@code proxy_read_timeout}(鎖全体の上限)。 */
    private static final Duration NGINX_CHAIN_CAP = Duration.ofSeconds(1200);

    private static RouteProperties.Route route(String id) throws IOException {
        Path repoRoot = findRepoRoot();
        Path ymlPath = repoRoot.resolve("services/gateway/src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(ymlPath), () -> "application.ymlが見つかりません: " + ymlPath);

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load("application", new FileSystemResource(ymlPath));
        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));
        RouteProperties properties = binder.bind("app.gateway", RouteProperties.class)
                .orElseThrow(() -> new IllegalStateException("app.gatewayの束縛に失敗しました: " + ymlPath));
        return properties.getRoutes().stream()
                .filter(r -> id.equals(r.getId()))
                .findFirst()
                .orElse(null);
    }

    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("settings.gradleが見つからずリポジトリルートを特定できませんでした");
    }

    @Test
    @DisplayName("platformルートは実測のビルド時間に見合うresponse-timeoutを持ち、既定の60秒のままではない")
    void platformルートのタイムアウトは実測のビルド時間を待ちきれる() throws IOException {
        RouteProperties.Route platformRoute = route("platform");

        assertNotNull(platformRoute, "platformルートがありません");
        assertTrue(platformRoute.getPaths().contains("/api/system/**"),
                "platformルートが/api/system/**を担当していません: " + platformRoute.getPaths());
        assertNotNull(platformRoute.getResponseTimeout(),
                "platformルートにresponse-timeoutの上書きがありません(既定の60秒のままだと、"
                        + "VSCode拡張ビルド1回だけでも504になりうる)");
        assertTrue(platformRoute.getResponseTimeout().compareTo(MEASURED_WORST_CASE_FLOOR) >= 0,
                "response-timeout(" + platformRoute.getResponseTimeout() + ")が実測の最悪ケースに見合う"
                        + "下限(" + MEASURED_WORST_CASE_FLOOR + ")より短い");
        assertTrue(platformRoute.getResponseTimeout().compareTo(NGINX_CHAIN_CAP) <= 0,
                "response-timeout(" + platformRoute.getResponseTimeout() + ")がnginxの鎖の上限("
                        + NGINX_CHAIN_CAP + ")を超えている。前段のnginxがそれより先に接続を切るため、"
                        + "この設定は効かない");
    }
}
