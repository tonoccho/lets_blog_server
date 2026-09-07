package com.letsblog.media.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像生成({@code POST /api/ai/image})のタイムアウトが、
 * <b>nginx ≧ gateway ≧ media の最悪ケース</b>という鎖として一貫していることを固定する
 * (issue #1102 レビュー指摘の BLOCKING)。
 *
 * <p><b>なぜ必要か</b>: #1102の当初実装はgatewayの{@code ai-image}ルートに
 * {@code response-timeout: 18000s}(5時間)を置いたが、実クライアントのトラフィックは
 * 全て{@code lbs-reverse-proxy}を経由し(docker-compose.yml冒頭)、nginxの
 * {@code location /api/}が{@code proxy_read_timeout 1200s}(20分)で頭打ちにしていたため、
 * gatewayの設定は事実上死んでいた。<b>1層だけを見るテストではこれを捕まえられない</b>ので、
 * 3層すべての設定ファイルを読んで不等式を固定する。
 *
 * <p><b>media側の最悪ケース</b>:
 * <pre>
 *   NON_POLLING_OVERHEAD_SECONDS + batchCountの上限 × ComfyUiClient.maxPollSeconds(batchSizeの上限)
 * </pre>
 * ポーリング上限は{@link ComfyUiClient}の実際の値を使う。テスト側で式を書き写すと、
 * 定数を変えたときに鎖が黙って壊れるため。
 *
 * <p><b>鎖全体の上限は3600秒</b>にする。これはこのリポジトリのnginxが既に
 * {@code /api/dashboard/}(SSE)と{@code /penpot}に与えている値で、これを超える長さを
 * 同期リクエストに与えないという方針の表明でもある。
 */
@DisplayName("画像生成のタイムアウトの鎖: nginx ≧ gateway ≧ media (issue #1102)")
class ImageGenerationTimeoutChainTest {

    /** {@code AiImageRequest}の{@code @Max}が許す1投入あたりの枚数。 */
    private static final int MAX_BATCH_SIZE = 16;

    /** {@code AiImageRequest}の{@code @Max}が許すリピート回数。 */
    private static final int MAX_BATCH_COUNT = 16;

    /**
     * ポーリング以外に1リクエストで掛かりうる時間の見積り(秒)。
     *
     * <ul>
     *   <li>タグ提案のLLM呼び出し(ai-serviceのread timeoutと同じ180秒)</li>
     *   <li>{@code generation_jobs}の作成・完了(2往復)</li>
     *   <li>最大256枚の{@code /view}取得・Base64化・{@code generated_images}への保存</li>
     * </ul>
     *
     * <p>ポーリング予算だけを鎖の根拠にすると、これらの時間ぶんだけgatewayが先に
     * 切れてしまう。余裕として明示的に足す。
     */
    private static final int NON_POLLING_OVERHEAD_SECONDS = 300;

    /** 鎖全体に許す上限(秒)。 */
    private static final int CHAIN_CAP_SECONDS = 3600;

    /** media-serviceが1リクエストで掛かりうる最悪の時間(秒)。 */
    private static long mediaWorstCaseSeconds() {
        return (long) NON_POLLING_OVERHEAD_SECONDS
                + (long) MAX_BATCH_COUNT * ComfyUiClient.maxPollSeconds(MAX_BATCH_SIZE);
    }

    @Test
    @DisplayName("media自身の最悪ケースが鎖の上限(3600秒)に収まる")
    void mediaの最悪ケースが鎖の上限に収まる() {
        long worst = mediaWorstCaseSeconds();

        assertTrue(worst <= CHAIN_CAP_SECONDS,
                "mediaの最悪ケース" + worst + "秒が鎖の上限" + CHAIN_CAP_SECONDS
                        + "秒を超えています。ComfyUiClientのポーリング予算を見直してください");
    }

    @Test
    @DisplayName("gatewayのresponse-timeoutはmediaの最悪ケース以上で、鎖の上限以内")
    void gatewayはmediaを待ちきり鎖の上限を超えない() throws IOException {
        long gateway = gatewayResponseTimeoutSeconds("ai-image");
        long worst = mediaWorstCaseSeconds();

        assertTrue(gateway >= worst,
                "gatewayのresponse-timeout(" + gateway + "秒)がmediaの最悪ケース("
                        + worst + "秒)より短く、生成中に504になりえます");
        assertTrue(gateway <= CHAIN_CAP_SECONDS,
                "gatewayのresponse-timeout(" + gateway + "秒)が鎖の上限("
                        + CHAIN_CAP_SECONDS + "秒)を超えています");
    }

    @Test
    @DisplayName("nginxは /api/ai/image 専用のlocationを持ち、gateway以上のタイムアウトを与える")
    void nginxはgatewayを待ちきる() throws IOException {
        Map<String, Long> timeouts = nginxTimeoutsForAiImage();
        long gateway = gatewayResponseTimeoutSeconds("ai-image");

        Long read = timeouts.get("proxy_read_timeout");
        Long send = timeouts.get("proxy_send_timeout");
        assertNotNull(read, "nginxの /api/ai/image のlocationに proxy_read_timeout がありません");
        assertNotNull(send, "nginxの /api/ai/image のlocationに proxy_send_timeout がありません");

        assertTrue(read >= gateway,
                "nginxのproxy_read_timeout(" + read + "秒)がgatewayのresponse-timeout("
                        + gateway + "秒)より短いため、gatewayに何を設定しても効きません");
        assertTrue(send >= gateway,
                "nginxのproxy_send_timeout(" + send + "秒)がgatewayのresponse-timeout("
                        + gateway + "秒)より短いです");
        assertTrue(read <= CHAIN_CAP_SECONDS,
                "nginxのproxy_read_timeout(" + read + "秒)が鎖の上限("
                        + CHAIN_CAP_SECONDS + "秒)を超えています");
    }

    /**
     * 参照系の{@code /api/ai/image-options}が生成用の長いタイムアウトを共有しないこと。
     * nginxのlocationを前方一致({@code location /api/ai/image})にすると
     * {@code /api/ai/image-options}まで巻き込むため、完全一致にしてある。
     */
    @Test
    @DisplayName("参照系 /api/ai/image-options は生成用の長いタイムアウトを共有しない")
    void 参照系は長いタイムアウトを共有しない() throws IOException {
        long options = gatewayResponseTimeoutSeconds("ai-image-options");

        assertTrue(options <= 600,
                "参照系 /api/ai/image-options のタイムアウトが長すぎます: " + options + "秒");
        assertTrue(nginxAiImageLocationIsExactMatch(),
                "nginxの /api/ai/image は完全一致(location = /api/ai/image)にしてください。"
                        + "前方一致だと /api/ai/image-options まで長いタイムアウトを共有します");
    }

    // ------------------------------------------------------------------
    // 設定ファイルの読み取り
    // ------------------------------------------------------------------

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("settings.gradleが見つからずリポジトリルートを特定できませんでした");
    }

    /** gatewayのapplication.ymlから、指定したルートidのresponse-timeoutを秒で読む。 */
    @SuppressWarnings("unchecked")
    private static long gatewayResponseTimeoutSeconds(String routeId) throws IOException {
        Path yml = repoRoot().resolve("services/gateway/src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(yml), () -> "application.ymlが見つかりません: " + yml);

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(yml)) {
            root = new Yaml().load(in);
        }
        Map<String, Object> app = (Map<String, Object>) root.get("app");
        assertNotNull(app, "application.ymlに app がありません");
        Map<String, Object> gateway = (Map<String, Object>) app.get("gateway");
        assertNotNull(gateway, "application.ymlに app.gateway がありません");
        List<Map<String, Object>> routes = (List<Map<String, Object>>) gateway.get("routes");
        assertNotNull(routes, "application.ymlに app.gateway.routes がありません");

        for (Map<String, Object> route : routes) {
            if (routeId.equals(route.get("id"))) {
                Object timeout = route.get("response-timeout");
                assertNotNull(timeout, "ルート" + routeId + "にresponse-timeoutがありません");
                return parseSeconds(String.valueOf(timeout));
            }
        }
        throw new AssertionError("gatewayに" + routeId + "ルートがありません");
    }

    /** {@code 3400s} / {@code 60m} / {@code 1h} / 数値のみ(秒) を秒へ直す。 */
    private static long parseSeconds(String value) {
        Matcher m = Pattern.compile("^(\\d+)(ms|s|m|h)?$").matcher(value.trim());
        assertTrue(m.matches(), "期間として解釈できません: " + value);
        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "s" : m.group(2);
        return switch (unit) {
            case "ms" -> amount / 1000;
            case "m" -> amount * 60;
            case "h" -> amount * 3600;
            default -> amount;
        };
    }

    private static String nginxConf() throws IOException {
        Path conf = repoRoot().resolve("infra/nginx/conf.d/default.conf");
        assertTrue(Files.isRegularFile(conf), () -> "nginxの設定が見つかりません: " + conf);
        return new String(Files.readAllBytes(conf), StandardCharsets.UTF_8);
    }

    /** {@code location [=] /api/ai/image} ブロックのproxyタイムアウトを秒で返す。 */
    private static Map<String, Long> nginxTimeoutsForAiImage() throws IOException {
        String block = nginxAiImageLocationBlock();
        assertNotNull(block, "nginxに /api/ai/image 専用のlocationがありません。"
                + "location /api/ の proxy_read_timeout がgatewayの設定を頭打ちにします");

        Map<String, Long> result = new java.util.LinkedHashMap<>();
        Matcher m = Pattern.compile("(proxy_read_timeout|proxy_send_timeout)\\s+([0-9]+[a-z]*)\\s*;")
                .matcher(block);
        while (m.find()) {
            result.put(m.group(1), parseSeconds(m.group(2)));
        }
        return result;
    }

    private static boolean nginxAiImageLocationIsExactMatch() throws IOException {
        return Pattern.compile("location\\s+=\\s+/api/ai/image\\s*\\{").matcher(nginxConf()).find();
    }

    /** 対象locationの本文({@code {} }の中身)を返す。無ければnull。 */
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
}
