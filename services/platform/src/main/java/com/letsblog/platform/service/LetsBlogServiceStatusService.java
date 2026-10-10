package com.letsblog.platform.service;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Let's Blog 自身のマイクロサービス9つの稼働状況(issue #589)。
 *
 * <p>{@link ConnectedServiceStatusService} が見ているのは<b>外部依存</b>
 * (DB・LLM・ComfyUI・PlantUML・WordPressエージェント・Penpot・Brave Search)だけで、
 * サービス自身の状態はダッシュボードに出ていなかった。サービスが11個になった構成では
 * 「どれが落ちているか」が一目で分からないと運用できない、というのが #589 の出発点である。
 *
 * <h2>gatewayの集約ヘルスを読む(各サービスを個別に叩かない)</h2>
 *
 * <p>gatewayは既に下流9サービスの{@code /actuator/health}を集約している(#560、#643、#743)。
 * platform-serviceから9本を別途叩くと、同じ判定が2箇所に分かれて食い違いうる。
 * <b>gatewayの集約結果を展開する</b>ことで、判定の出所を1つに保つ。
 *
 * <p>gateway自体に到達できない場合は、9サービスすべてを「判定不能」としてERRORで返す。
 * 実運用ではgatewayが落ちていればWeb自体も応答しないが、コンテナ間の疎通だけが切れている
 * ケースを黙って「正常」に見せないため。
 *
 * <h2>停止したときに何が使えなくなるか</h2>
 *
 * <p>各サービスに「落ちると何ができなくなるか」を1行で持たせる({@link #IMPACT})。
 * サービス名だけでは、運用する人がその影響を判断できないため(#589 の受入基準)。
 */
@Service
public class LetsBlogServiceStatusService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * gatewayの集約ヘルスの読み取りタイムアウト。
     *
     * <p><b>gateway自身が下流1本あたり5秒待つ</b>({@code DownstreamHealthConfig})ため、
     * 他のチェックと同じ3秒にすると、下流が1つ落ちているときに毎回こちら側のタイムアウトが
     * 先に発火し、「gatewayへ到達できない」と誤判定して9件すべてを判定不能にしてしまう。
     * つまり<b>1つ落ちた瞬間に、どれが落ちたのか分からなくなる</b>(issue #589 の実機検証で発生)。
     * gatewayの待ち時間より確実に長くする。
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    /**
     * gatewayの集約ヘルスのコンポーネント名 → (表示名, 停止時の影響)。
     *
     * <p>コンポーネント名は{@code DownstreamHealthConfig}のBean名から Spring Boot が導出する
     * ({@code identityServiceHealthIndicator} → {@code identityService})。
     * サービスを増やしたら、あちらのBeanとこの表の両方へ足すこと。
     */
    private static final Map<String, ServiceMeta> IMPACT = new LinkedHashMap<>();

    static {
        IMPACT.put("identityService", new ServiceMeta("identity-service",
                "ログイン後の操作者解決が止まり、ほぼ全ての画面で権限判定に失敗します(認可は拒否側に倒れます)"));
        IMPACT.put("projectService", new ServiceMeta("project-service",
                "プロジェクト・サイトの参照と更新、SSH鍵とタグデザインの設定ができなくなります"));
        IMPACT.put("contentService", new ServiceMeta("content-service",
                "記事本文の編集・プレビュー、カスタムタグ、組み込みタグの展開ができなくなります"));
        IMPACT.put("mediaService", new ServiceMeta("media-service",
                "画像生成、生成画像の閲覧、ダイアグラム描画、ComfyUIモデル管理ができなくなります"));
        IMPACT.put("aiService", new ServiceMeta("ai-service",
                "文章生成・校正・タグ提案・記事プランの壁打ちができなくなります"));
        IMPACT.put("analyticsService", new ServiceMeta("analytics-service",
                "プロジェクトダッシュボードのGA/AdSenseレポートが表示できなくなります"));
        IMPACT.put("publishingService", new ServiceMeta("publishing-service",
                "記事の公開・削除、一括管理、環境間比較ができなくなります"));
        IMPACT.put("platformService", new ServiceMeta("platform-service",
                "システム設定、バックアップ、VSCode拡張の配布ができなくなります(この画面自体も表示できません)"));
        IMPACT.put("logWriterService", new ServiceMeta("log-writer",
                "監査ログ・操作ログ・フロントエンドエラーログの記録と閲覧が止まります"));
    }

    record ServiceMeta(String displayName, String impact) {
    }

    /** 1サービス分の判定結果。 */
    public record ServiceHealth(String id, String name, boolean up, String impact, String detail) {
    }

    private final RestClient gatewayClient;
    private final String gatewayUri;

    @Autowired
    public LetsBlogServiceStatusService(@Value("${app.gateway-uri}") String gatewayUri) {
        this(gatewayUri, defaultBuilder(gatewayUri));
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取る。 */
    LetsBlogServiceStatusService(String gatewayUri, RestClient.Builder builder) {
        preferJackson2(builder);
        this.gatewayClient = builder.build();
        this.gatewayUri = gatewayUri;
    }

    private static RestClient.Builder defaultBuilder(String gatewayUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor("gateway-status")).baseUrl(gatewayUri).requestFactory(requestFactory);
    }

    /** チェック対象URL(ダッシュボードの詳細診断に出す)。 */
    public String targetUrl() {
        return gatewayUri + "/actuator/health";
    }

    /**
     * 9サービスの稼働状況。gatewayへ到達できない場合は全件を「判定不能」(up=false)で返す。
     */
    public List<ServiceHealth> checkAll() {
        JsonNode components;
        try {
            // 下流が1つでもDOWNだと集約ヘルスは HTTP 503 を返す。既定の retrieve() は 5xx で
            // 例外にするため、そのままでは「gatewayへ到達できない」と誤判定して9件すべてを
            // 判定不能にしてしまい、どのサービスが落ちているかが分からなくなる。
            // ステータスコードでは弾かず、本文を読んで components を見る。
            JsonNode body = gatewayClient.get()
                    .uri("/actuator/health")
                    .retrieve()
                    .onStatus(status -> true, (request, response) -> { })
                    .body(JsonNode.class);
            components = body == null ? null : body.get("components");
        } catch (RestClientException e) {
            return unknownAll("gatewayの集約ヘルスを取得できませんでした: " + e.getMessage());
        }
        if (components == null) {
            return unknownAll("gatewayの集約ヘルスに components がありません");
        }

        List<ServiceHealth> result = new ArrayList<>();
        for (Map.Entry<String, ServiceMeta> entry : IMPACT.entrySet()) {
            JsonNode component = components.get(entry.getKey());
            if (component == null) {
                // gatewayのDownstreamHealthConfigに対応するBeanが無い = 監視対象から漏れている。
                // 「正常」にはせず、漏れとして見えるようにする。
                result.add(new ServiceHealth(entry.getKey(), entry.getValue().displayName(), false,
                        entry.getValue().impact(),
                        "gatewayの集約ヘルスに含まれていません(DownstreamHealthConfigへの追加漏れ)"));
                continue;
            }
            String status = component.path("status").asText("");
            boolean up = "UP".equals(status);
            String detail = up ? null : "status=" + (status.isEmpty() ? "(不明)" : status);
            result.add(new ServiceHealth(
                    entry.getKey(), entry.getValue().displayName(), up, entry.getValue().impact(), detail));
        }
        return result;
    }

    private List<ServiceHealth> unknownAll(String reason) {
        List<ServiceHealth> result = new ArrayList<>();
        IMPACT.forEach((id, meta) ->
                result.add(new ServiceHealth(id, meta.displayName(), false, meta.impact(), reason)));
        return result;
    }

    /**
     * Boot 4ではRestClientの既定JSONコンバータがJackson3(tools.jackson)になったが、本クラスは
     * com.fasterxml.jackson.databind.JsonNode(Jackson2)でレスポンスを読む
     * (ContainerStatusService/KeycloakAdminClientと同じ理由)。
     */
    private static void preferJackson2(RestClient.Builder builder) {
        builder.messageConverters(converters -> {
            converters.removeIf(AbstractJacksonHttpMessageConverter.class::isInstance);
            converters.add(0, new MappingJackson2HttpMessageConverter());
        });
    }
}
