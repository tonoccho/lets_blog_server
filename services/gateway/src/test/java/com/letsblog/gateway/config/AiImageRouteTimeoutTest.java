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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1102: 画像生成({@code POST /api/ai/image})の{@code response-timeout}が、
 * <b>前段のnginxと後段のmediaの両方と整合している</b>ことを固定する。
 *
 * <p><b>gatewayだけを見ても意味が無い</b>。実クライアントのトラフィックは全て
 * {@code lbs-reverse-proxy}を経由し(docker-compose.yml冒頭)、nginxの
 * {@code location /api/}は{@code proxy_read_timeout 1200s}で頭打ちにする。
 * #1102の当初実装はここに{@code 18000s}(5時間)を置いたが、nginxが20分で切るため
 * 一度も効いていなかった(レビュー指摘のBLOCKING)。そこで
 * {@code infra/nginx/conf.d/default.conf}も読み、{@code nginx ≧ gateway}を見る。
 *
 * <p>{@code gateway ≧ mediaの最悪ケース}のほうは、{@code ComfyUiClient}の実定数を
 * 参照できるmedia側の{@code ImageGenerationTimeoutChainTest}が担当する
 * (ここで式を書き写すと定数を変えたときに黙ってずれるため)。gateway側では
 * <b>鎖全体の上限3600秒</b>と、その中でmediaの最悪ケースを賄える下限だけを見る。
 *
 * <p>3600秒はこのリポジトリのnginxが既に{@code /api/dashboard/}(SSE)と
 * {@code /penpot}に与えている値で、同期リクエストにこれ以上の長さを与えないという
 * 方針の表明でもある。
 *
 * <p>逆に{@code /api/ai/image-options}(モデル一覧の参照)は数秒で終わる参照系で、
 * 同じ長さのタイムアウトを与えると、ComfyUIが無応答のときに接続を何時間も掴んだままになる。
 * そのため#1102でルートを分け、こちらは短いままにしている。それが元に戻っていないことも
 * ここで見る。
 */
@DisplayName("gateway: 画像生成ルートのresponse-timeout(issue #1102)")
class AiImageRouteTimeoutTest {

    /**
     * 鎖全体に許す上限。nginxが既に{@code /api/dashboard/}と{@code /penpot}へ
     * 与えている値に合わせる。
     */
    private static final Duration CHAIN_CAP = Duration.ofSeconds(3600);

    /**
     * media-serviceの最悪ケースの下限見積り。実測(512×512・20ステップで16枚17秒)の
     * 10倍以上の余裕を持つポーリング予算 60 + 8 × batchSize 秒に、batchCountの上限16と
     * ポーリング以外の見積り300秒を足すと3308秒になる。正確な値は
     * {@code ImageGenerationTimeoutChainTest}(media側)が{@code ComfyUiClient}の
     * 実定数から計算する。ここでは「明らかに足りない値へ下げられていないこと」だけを見る。
     */
    private static final Duration MEDIA_WORST_CASE_FLOOR = Duration.ofSeconds(3308);

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
    @DisplayName("POST /api/ai/image のタイムアウトは最大枚数(16×16)の生成を待ちきり、鎖の上限を超えない")
    void 画像生成ルートは最悪ケースを待ちきれる() throws IOException {
        RouteProperties.Route imageRoute = route("ai-image");

        assertNotNull(imageRoute, "ai-imageルートがありません");
        assertTrue(imageRoute.getPaths().contains("/api/ai/image"),
                "ai-imageルートが/api/ai/imageを担当していません: " + imageRoute.getPaths());
        assertNotNull(imageRoute.getResponseTimeout(), "ai-imageルートにresponse-timeoutがありません");
        assertTrue(imageRoute.getResponseTimeout().compareTo(MEDIA_WORST_CASE_FLOOR) >= 0,
                "response-timeout(" + imageRoute.getResponseTimeout() + ")がmediaの最悪ケース("
                        + MEDIA_WORST_CASE_FLOOR + ")より短いため、生成中に504になりうる");
        assertTrue(imageRoute.getResponseTimeout().compareTo(CHAIN_CAP) <= 0,
                "response-timeout(" + imageRoute.getResponseTimeout() + ")が鎖の上限("
                        + CHAIN_CAP + ")を超えている。前段のnginxがそれより先に接続を切るため、"
                        + "この設定は効かない");
    }

    /**
     * nginxが{@code /api/ai/image}専用のlocationを持ち、gatewayのresponse-timeout以上の
     * {@code proxy_read_timeout}/{@code proxy_send_timeout}を与えていること。
     * これが無いと{@code location /api/}の1200sが上限になり、gatewayの設定は死ぬ。
     */
    @Test
    @DisplayName("nginxの /api/ai/image はgatewayのresponse-timeout以上を与える")
    void nginxがgatewayを頭打ちにしない() throws IOException {
        RouteProperties.Route imageRoute = route("ai-image");
        assertNotNull(imageRoute, "ai-imageルートがありません");
        Duration gateway = imageRoute.getResponseTimeout();

        String block = nginxAiImageLocationBlock();
        assertNotNull(block, "nginxに /api/ai/image 専用のlocationがありません("
                + "infra/nginx/conf.d/default.conf)。location /api/ の proxy_read_timeout が"
                + "gatewayの設定を頭打ちにする");

        Duration read = nginxTimeout(block, "proxy_read_timeout");
        Duration send = nginxTimeout(block, "proxy_send_timeout");
        assertNotNull(read, "nginxの /api/ai/image に proxy_read_timeout がありません");
        assertNotNull(send, "nginxの /api/ai/image に proxy_send_timeout がありません");

        assertTrue(read.compareTo(gateway) >= 0,
                "nginxのproxy_read_timeout(" + read + ")がgatewayのresponse-timeout("
                        + gateway + ")より短いため、gatewayに何を設定しても効かない");
        assertTrue(send.compareTo(gateway) >= 0,
                "nginxのproxy_send_timeout(" + send + ")がgatewayのresponse-timeout("
                        + gateway + ")より短い");
        assertTrue(read.compareTo(CHAIN_CAP) <= 0,
                "nginxのproxy_read_timeout(" + read + ")が鎖の上限(" + CHAIN_CAP + ")を超えている");
    }

    @Test
    @DisplayName("GET /api/ai/image-options は参照系なので長いタイムアウトを共有しない")
    void 画像生成オプションのルートは短いままにする() throws IOException {
        RouteProperties.Route optionsRoute = route("ai-image-options");

        assertNotNull(optionsRoute, "ai-image-optionsルートがありません(画像生成と同じ長さの"
                + "タイムアウトを共有していないか確認すること)");
        assertTrue(optionsRoute.getPaths().contains("/api/ai/image-options"),
                "ai-image-optionsルートが/api/ai/image-optionsを担当していません: " + optionsRoute.getPaths());
        assertNotNull(optionsRoute.getResponseTimeout(),
                "ai-image-optionsルートにresponse-timeoutがありません");
        assertTrue(optionsRoute.getResponseTimeout().compareTo(Duration.ofMinutes(10)) <= 0,
                "参照系のタイムアウトが長すぎます: " + optionsRoute.getResponseTimeout());
    }

    private static String nginxConf() throws IOException {
        Path conf = findRepoRoot().resolve("infra/nginx/conf.d/default.conf");
        assertTrue(Files.isRegularFile(conf), () -> "nginxの設定が見つかりません: " + conf);
        return new String(Files.readAllBytes(conf), StandardCharsets.UTF_8);
    }

    /** {@code location [=] /api/ai/image} ブロックの本文。無ければnull。 */
    private static String nginxAiImageLocationBlock() throws IOException {
        String conf = nginxConf();
        Matcher m = Pattern.compile("location\\s+=?\\s*/api/ai/image\\s*\\{").matcher(conf);
        if (!m.find()) {
            return null;
        }
        int depth = 0;
        int start = m.end();
        for (int i = m.end() - 1; i < conf.length(); i++) {
            char c = conf.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return conf.substring(start, i);
                }
            }
        }
        throw new AssertionError("nginxの /api/ai/image のlocationが閉じていません");
    }

    /** nginxの {@code <directive> <値>;} を Duration にする。無ければnull。 */
    private static Duration nginxTimeout(String block, String directive) {
        Matcher m = Pattern.compile(directive + "\\s+([0-9]+)([a-z]*)\\s*;").matcher(block);
        if (!m.find()) {
            return null;
        }
        long amount = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofSeconds(amount);
        };
    }
}
