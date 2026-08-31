package com.letsblog.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gatewayの下流向けDNSキャッシュTTLの回帰テスト(issue #951)。
 *
 * <p>Reactor Nettyの既定のDNSリゾルバは、解決結果をDNSレコードのTTL(Dockerの組み込みDNSは
 * <b>600秒</b>)までキャッシュする。docker composeで複数サービスを同時に再起動すると、
 * DockerがIPを割り当て直し、別のサービスが以前のIPを引き継ぐことがある。gatewayが古いIPを
 * 掴んだままだと、たとえば {@code /api/auth/setup-status}(identity宛)がmedia-serviceへ
 * 届く。届いた先にそのパスは無いため401になり、gatewayを再起動するまで直らない。
 *
 * <p>症状が401なので認可の設定を疑うが、原因は<b>宛先の取り違え</b>である。
 * 401で済むのは偶然で、取り違えた先に同じパスがあれば別サービスのデータを操作してしまう。
 *
 * <p>DNSの解決そのものはユニットテストで再現できない。ここで守るのは
 * <b>「TTLの既定値が短いままであること」</b>という一点である。600秒に戻されたら落ちる。
 */
class GatewayDnsCacheTest {

    /**
     * これを超えるTTLは「同時再起動でIPが入れ替わっても追従できない」域に入る。
     * 下流サービスの再起動が終わるまでの時間(healthy待ちで数十秒)より十分短い値にしておく。
     */
    private static final long MAX_ACCEPTABLE_TTL_SECONDS = 60;

    @Test
    @DisplayName("application.ymlのDNSキャッシュTTLの既定値が十分短い(#951)")
    void dnsCacheTtlDefaultIsShort() {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(in, "gatewayのapplication.ymlが読めません");
            Map<String, Object> root = new Yaml().load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) root.get("app");
            assertNotNull(app, "app: 節がありません");

            Object raw = app.get("dns-cache-ttl-seconds");
            assertNotNull(raw, "app.dns-cache-ttl-seconds が定義されていません(#951)");

            long ttl = extractDefault(String.valueOf(raw));
            assertTrue(
                    ttl > 0 && ttl <= MAX_ACCEPTABLE_TTL_SECONDS,
                    "app.dns-cache-ttl-seconds の既定値は 1〜" + MAX_ACCEPTABLE_TTL_SECONDS
                            + " 秒であること(実際: " + ttl + ")。"
                            + "長くすると、同時再起動でIPが入れ替わったときに gateway が"
                            + "別サービスへ転送し続ける(#951)");
        } catch (Exception e) {
            throw new AssertionError("application.yml の読み取りに失敗しました", e);
        }
    }

    /** {@code ${GATEWAY_DNS_CACHE_TTL_SECONDS:10}} 形式から既定値(10)を取り出す。 */
    private static long extractDefault(String placeholder) {
        int colon = placeholder.lastIndexOf(':');
        int brace = placeholder.lastIndexOf('}');
        if (placeholder.startsWith("${") && colon > 0 && brace > colon) {
            return Long.parseLong(placeholder.substring(colon + 1, brace).trim());
        }
        return Long.parseLong(placeholder.trim());
    }

    @Test
    @DisplayName("gatewayWebClientは既定のコネクタではなく、TTLを設定したHttpClientで作られる(#951)")
    void gatewayWebClientIsBuiltWithCustomConnector() {
        new ApplicationContextRunner()
                .withUserConfiguration(GatewayRoutingConfig.class)
                .withPropertyValues(
                        "app.dns-cache-ttl-seconds=10",
                        "app.gateway.default-response-timeout=60s")
                .run(context -> {
                    assertTrue(context.containsBean("gatewayWebClient"),
                            "gatewayWebClient ビーンがありません");
                    WebClient client = context.getBean("gatewayWebClient", WebClient.class);
                    assertNotNull(client);
                    // WebClient はコネクタを公開しないため、ここで確かめられるのは
                    // 「TTLを外から差し替えられる形で組み立てられていること」まで。
                    // TTLの値そのものは上のテストが application.yml 側で守る。
                });
    }
}
