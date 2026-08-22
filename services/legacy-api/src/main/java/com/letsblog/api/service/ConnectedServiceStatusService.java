package com.letsblog.api.service;

import com.letsblog.api.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.api.render.PlantUmlEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * ダッシュボードに表示する接続サービス(DB・外部連携)の稼働状況をチェックする(issue #181, #197)。
 * 各サービス固有の軽量なヘルスチェック用エンドポイント(または実際の処理能力を確認できる操作)を
 * 使って疎通確認する。短いタイムアウトで行い、ダッシュボード表示への影響(応答遅延)を抑える。
 */
@Service
public class ConnectedServiceStatusService {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String PLANTUML_HEALTHCHECK_SOURCE = "@startuml\nA->B\n@enduml";

    private final DataSource dataSource;
    private final RestClient comfyUiClient;
    private final RestClient plantUmlClient;
    private final RestClient wordpressProvisioningClient;
    private final RestClient penpotClient;
    private final SystemSettingService systemSettingService;
    private final String comfyUiBaseUrl;
    private final String plantUmlBaseUrl;
    private final String wordpressProvisionBaseUrl;
    private final String penpotBaseUrl;
    private final String llmApiKey;

    @Autowired
    public ConnectedServiceStatusService(
            DataSource dataSource,
            @Value("${app.llm-api-key}") String llmApiKey,
            @Value("${app.comfyui-base-url}") String comfyUiBaseUrl,
            @Value("${app.plantuml-base-url}") String plantUmlBaseUrl,
            @Value("${app.wordpress-provision-base-url}") String wordpressProvisionBaseUrl,
            @Value("${app.penpot-base-url}") String penpotBaseUrl,
            SystemSettingService systemSettingService) {
        this(dataSource,
                llmApiKey,
                builderWithTimeout(comfyUiBaseUrl), comfyUiBaseUrl,
                builderWithTimeout(plantUmlBaseUrl), plantUmlBaseUrl,
                builderWithTimeout(wordpressProvisionBaseUrl), wordpressProvisionBaseUrl,
                builderWithTimeout(penpotBaseUrl), penpotBaseUrl,
                systemSettingService);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    ConnectedServiceStatusService(
            DataSource dataSource,
            String llmApiKey,
            RestClient.Builder comfyUiBuilder, String comfyUiBaseUrl,
            RestClient.Builder plantUmlBuilder, String plantUmlBaseUrl,
            RestClient.Builder wordpressBuilder, String wordpressProvisionBaseUrl,
            RestClient.Builder penpotBuilder, String penpotBaseUrl,
            SystemSettingService systemSettingService) {
        this.dataSource = dataSource;
        this.llmApiKey = llmApiKey;
        this.comfyUiClient = comfyUiBuilder.build();
        this.plantUmlClient = plantUmlBuilder.build();
        this.wordpressProvisioningClient = wordpressBuilder.build();
        this.penpotClient = penpotBuilder.build();
        this.systemSettingService = systemSettingService;
        this.comfyUiBaseUrl = comfyUiBaseUrl;
        this.plantUmlBaseUrl = plantUmlBaseUrl;
        this.wordpressProvisionBaseUrl = wordpressProvisionBaseUrl;
        this.penpotBaseUrl = penpotBaseUrl;
    }

    private static RestClient.Builder builderWithTimeout(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory);
    }

    /** 稼働状況(正常/警告/エラーの3値)のみを返す。全ログインユーザーが参照できる(issue #181)。 */
    public List<ConnectedServiceStatusResponse> checkAll() {
        return checkAllDetailed().stream()
                .map(d -> new ConnectedServiceStatusResponse(d.id(), d.name(), d.status()))
                .toList();
    }

    /**
     * 応答時間・エラー内容・チェック対象URLなどを含む詳細診断情報を返す。admin限定(issue #199)。
     * 各サービスへの疎通確認を並列に実行する(issue #198でSSE配信の周期を短縮したため、
     * 直列実行だと最悪ケースでTIMEOUT×サービス数の遅延が生じうる問題を避ける)。
     */
    public List<ConnectedServiceStatusDetailResponse> checkAllDetailed() {
        CompletableFuture<ConnectedServiceStatusDetailResponse> comfyUi =
                checkAsync("comfyui", "ComfyUI", this::checkComfyUi);
        CompletableFuture<ConnectedServiceStatusDetailResponse> plantUml =
                checkAsync("plantuml", "PlantUML", this::checkPlantUml);
        CompletableFuture<ConnectedServiceStatusDetailResponse> wordpressProvisioning =
                checkAsync("wordpress-provisioning", "WordPress Provisioning Agent", this::checkWordpressProvisioning);
        CompletableFuture<ConnectedServiceStatusDetailResponse> penpot =
                checkAsync("penpot", "Penpot", this::checkPenpot);

        return List.of(
                runTimed("database", "データベース", this::checkDatabase),
                runTimed("llm", "LLM", this::checkLlm),
                comfyUi.join(),
                plantUml.join(),
                wordpressProvisioning.join(),
                penpot.join(),
                runTimed("brave-search", "Brave Search API", this::checkBraveSearch));
    }

    private CompletableFuture<ConnectedServiceStatusDetailResponse> checkAsync(
            String id, String name, Supplier<CheckOutcome> check) {
        return CompletableFuture.supplyAsync(() -> runTimed(id, name, check));
    }

    private ConnectedServiceStatusDetailResponse runTimed(String id, String name, Supplier<CheckOutcome> check) {
        long startedAt = System.currentTimeMillis();
        CheckOutcome outcome = check.get();
        long responseTimeMs = System.currentTimeMillis() - startedAt;
        return new ConnectedServiceStatusDetailResponse(
                id, name, outcome.status(), responseTimeMs,
                outcome.httpStatus(), outcome.errorMessage(), outcome.targetUrl(), Instant.now());
    }

    private CheckOutcome checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid((int) TIMEOUT.toSeconds())) {
                return CheckOutcome.normal(null);
            }
            return new CheckOutcome(Status.ERROR, null, "接続の検証に失敗しました", null);
        } catch (SQLException e) {
            return new CheckOutcome(Status.ERROR, null, e.getMessage(), null);
        }
    }

    /**
     * 外部ホスト型LLM APIは第三者の有料APIのため、疎通確認のために定期的に実リクエストを送ることはせず、
     * APIキーが設定されているかどうかを稼働状況の代わりとして扱う(Brave Searchと同じ方針)。
     */
    private CheckOutcome checkLlm() {
        if (llmApiKey != null && !llmApiKey.isBlank()) {
            return CheckOutcome.normal(null);
        }
        return new CheckOutcome(Status.WARNING, null, "APIキーが設定されていません", null);
    }

    /** ComfyUI公式の軽量なシステム状態エンドポイントで判定する。 */
    private CheckOutcome checkComfyUi() {
        return checkHttpService(comfyUiClient, comfyUiBaseUrl, "/system_stats");
    }

    /** provision-agentの専用ヘルスチェックルート(issue #197で追加)で判定する。 */
    private CheckOutcome checkWordpressProvisioning() {
        return checkHttpService(wordpressProvisioningClient, wordpressProvisionBaseUrl, "/health");
    }

    /** Penpotフロントエンドが公開しているヘルスチェック用エンドポイントで判定する。 */
    private CheckOutcome checkPenpot() {
        return checkHttpService(penpotClient, penpotBaseUrl, "/readyz");
    }

    /**
     * 単なる疎通確認ではなく、実際に最小限のPlantUML図をレンダリングできるかで判定する
     * (PlantUmlClientが使うのと同じ/png/{encoded}エンドポイント)。
     */
    private CheckOutcome checkPlantUml() {
        String path = "/png/" + PlantUmlEncoder.encode(PLANTUML_HEALTHCHECK_SOURCE);
        String targetUrl = plantUmlBaseUrl + path;
        try {
            ResponseEntity<byte[]> response = plantUmlClient.get().uri(path).retrieve().toEntity(byte[].class);
            byte[] png = response.getBody();
            int httpStatus = response.getStatusCode().value();
            if (png != null && png.length > 0) {
                return new CheckOutcome(Status.NORMAL, httpStatus, null, targetUrl);
            }
            return new CheckOutcome(Status.WARNING, httpStatus, "PNGデータが空です", targetUrl);
        } catch (RestClientResponseException e) {
            Status status = e.getStatusCode().is5xxServerError() ? Status.WARNING : Status.NORMAL;
            return new CheckOutcome(status, e.getStatusCode().value(), e.getMessage(), targetUrl);
        } catch (RestClientException e) {
            return new CheckOutcome(Status.ERROR, null, e.getMessage(), targetUrl);
        }
    }

    /**
     * 指定パスへのGETが何らかのHTTP応答を返すこと(4xxも含む)を「到達可能」とみなす。
     * 5xxはプロセスは生きているが異常応答のため警告、接続自体ができない場合はエラーとして扱う。
     */
    private CheckOutcome checkHttpService(RestClient client, String baseUrl, String path) {
        String targetUrl = baseUrl + path;
        try {
            ResponseEntity<Void> response = client.get().uri(path).retrieve().toBodilessEntity();
            return new CheckOutcome(Status.NORMAL, response.getStatusCode().value(), null, targetUrl);
        } catch (RestClientResponseException e) {
            Status status = e.getStatusCode().is5xxServerError() ? Status.WARNING : Status.NORMAL;
            return new CheckOutcome(status, e.getStatusCode().value(), e.getMessage(), targetUrl);
        } catch (RestClientException e) {
            return new CheckOutcome(Status.ERROR, null, e.getMessage(), targetUrl);
        }
    }

    /**
     * Brave Search APIは第三者の有料APIのため、疎通確認のために定期的に実リクエストを送ることはせず、
     * APIキーが設定されているかどうかを稼働状況の代わりとして扱う。
     */
    private CheckOutcome checkBraveSearch() {
        if (systemSettingService.getBraveSearchApiKeyStatus().configured()) {
            return CheckOutcome.normal(null);
        }
        return new CheckOutcome(Status.WARNING, null, "APIキーが設定されていません", null);
    }

    /** 各チェックの結果(issue #199の詳細診断用フィールドを含む)。targetUrlはHTTPを伴わないチェックではnull。 */
    private record CheckOutcome(Status status, Integer httpStatus, String errorMessage, String targetUrl) {
        private static CheckOutcome normal(String targetUrl) {
            return new CheckOutcome(Status.NORMAL, null, null, targetUrl);
        }
    }
}
