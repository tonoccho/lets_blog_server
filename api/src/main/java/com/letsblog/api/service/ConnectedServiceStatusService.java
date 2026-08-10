package com.letsblog.api.service;

import com.letsblog.api.dto.ConnectedServiceStatusResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.api.render.PlantUmlEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final RestClient ollamaClient;
    private final RestClient comfyUiClient;
    private final RestClient plantUmlClient;
    private final RestClient wordpressProvisioningClient;
    private final SystemSettingService systemSettingService;

    @Autowired
    public ConnectedServiceStatusService(
            DataSource dataSource,
            @Value("${app.ollama-base-url}") String ollamaBaseUrl,
            @Value("${app.comfyui-base-url}") String comfyUiBaseUrl,
            @Value("${app.plantuml-base-url}") String plantUmlBaseUrl,
            @Value("${app.wordpress-provision-base-url}") String wordpressProvisionBaseUrl,
            SystemSettingService systemSettingService) {
        this(dataSource,
                builderWithTimeout(ollamaBaseUrl),
                builderWithTimeout(comfyUiBaseUrl),
                builderWithTimeout(plantUmlBaseUrl),
                builderWithTimeout(wordpressProvisionBaseUrl),
                systemSettingService);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    ConnectedServiceStatusService(
            DataSource dataSource,
            RestClient.Builder ollamaBuilder,
            RestClient.Builder comfyUiBuilder,
            RestClient.Builder plantUmlBuilder,
            RestClient.Builder wordpressBuilder,
            SystemSettingService systemSettingService) {
        this.dataSource = dataSource;
        this.ollamaClient = ollamaBuilder.build();
        this.comfyUiClient = comfyUiBuilder.build();
        this.plantUmlClient = plantUmlBuilder.build();
        this.wordpressProvisioningClient = wordpressBuilder.build();
        this.systemSettingService = systemSettingService;
    }

    private static RestClient.Builder builderWithTimeout(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory);
    }

    /**
     * 各サービスへの疎通確認を並列に実行する(issue #198でSSE配信の周期を短縮したため、
     * 直列実行だと最悪ケースでTIMEOUT×サービス数の遅延が生じうる問題を避ける)。
     * DB確認・Brave Search判定は元々ミリ秒未満で終わるためそのまま直列に含める。
     */
    public List<ConnectedServiceStatusResponse> checkAll() {
        CompletableFuture<ConnectedServiceStatusResponse> ollama = checkAsync("ollama", "Ollama", this::checkOllama);
        CompletableFuture<ConnectedServiceStatusResponse> comfyUi = checkAsync("comfyui", "ComfyUI", this::checkComfyUi);
        CompletableFuture<ConnectedServiceStatusResponse> plantUml = checkAsync("plantuml", "PlantUML", this::checkPlantUml);
        CompletableFuture<ConnectedServiceStatusResponse> wordpressProvisioning = checkAsync(
                "wordpress-provisioning", "WordPress Provisioning Agent", this::checkWordpressProvisioning);

        return List.of(
                new ConnectedServiceStatusResponse("database", "データベース", checkDatabase()),
                ollama.join(),
                comfyUi.join(),
                plantUml.join(),
                wordpressProvisioning.join(),
                new ConnectedServiceStatusResponse("brave-search", "Brave Search API", checkBraveSearch()));
    }

    private CompletableFuture<ConnectedServiceStatusResponse> checkAsync(String id, String name, Supplier<Status> check) {
        return CompletableFuture.supplyAsync(() -> new ConnectedServiceStatusResponse(id, name, check.get()));
    }

    private Status checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid((int) TIMEOUT.toSeconds()) ? Status.NORMAL : Status.ERROR;
        } catch (SQLException e) {
            return Status.ERROR;
        }
    }

    /** モデル一覧を取得できるか(OllamaClient#listModelsが使うのと同じエンドポイント)で判定する。 */
    private Status checkOllama() {
        return checkHttpService(ollamaClient, "/api/tags");
    }

    /** ComfyUI公式の軽量なシステム状態エンドポイントで判定する。 */
    private Status checkComfyUi() {
        return checkHttpService(comfyUiClient, "/system_stats");
    }

    /** provision-agentの専用ヘルスチェックルート(issue #197で追加)で判定する。 */
    private Status checkWordpressProvisioning() {
        return checkHttpService(wordpressProvisioningClient, "/health");
    }

    /**
     * 単なる疎通確認ではなく、実際に最小限のPlantUML図をレンダリングできるかで判定する
     * (PlantUmlClientが使うのと同じ/png/{encoded}エンドポイント)。
     */
    private Status checkPlantUml() {
        try {
            String encoded = PlantUmlEncoder.encode(PLANTUML_HEALTHCHECK_SOURCE);
            byte[] png = plantUmlClient.get().uri("/png/" + encoded).retrieve().body(byte[].class);
            return (png != null && png.length > 0) ? Status.NORMAL : Status.WARNING;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().is5xxServerError() ? Status.WARNING : Status.NORMAL;
        } catch (RestClientException e) {
            return Status.ERROR;
        }
    }

    /**
     * 指定パスへのGETが何らかのHTTP応答を返すこと(4xxも含む)を「到達可能」とみなす。
     * 5xxはプロセスは生きているが異常応答のため警告、接続自体ができない場合はエラーとして扱う。
     */
    private Status checkHttpService(RestClient client, String path) {
        try {
            client.get().uri(path).retrieve().toBodilessEntity();
            return Status.NORMAL;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().is5xxServerError() ? Status.WARNING : Status.NORMAL;
        } catch (RestClientException e) {
            return Status.ERROR;
        }
    }

    /**
     * Brave Search APIは第三者の有料APIのため、疎通確認のために定期的に実リクエストを送ることはせず、
     * APIキーが設定されているかどうかを稼働状況の代わりとして扱う。
     */
    private Status checkBraveSearch() {
        return systemSettingService.getBraveSearchApiKeyStatus().configured() ? Status.NORMAL : Status.WARNING;
    }
}
