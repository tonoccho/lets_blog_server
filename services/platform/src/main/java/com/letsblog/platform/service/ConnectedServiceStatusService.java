package com.letsblog.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.platform.ai.AiProvider;
import com.letsblog.platform.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.platform.render.PlantUmlEncoder;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * legacy-apiから移設(issue #695、C10-3、元は issue #181, #197)。ダッシュボードに表示する接続サービス
 * (DB・外部連携)の稼働状況をチェックする。各サービス固有の軽量なヘルスチェック用エンドポイント
 * (または実際の処理能力を確認できる操作)を使って疎通確認する。短いタイムアウトで行い、
 * ダッシュボード表示への影響(応答遅延)を抑える。
 *
 * <p>移設前(legacy-api)はBrave Search APIキーの設定有無をPlatformServiceClient経由でplatform-service
 * (本サービス)へ問い合わせていたが、本サービス内へ移設されたことで{@link SystemSettingService}を
 * 直接注入する同一プロセス内呼び出しに置き換わった(C10-1、issue #693で既にSystemSettingServiceが
 * 本サービスに存在することが前提)。
 */
@Service
public class ConnectedServiceStatusService {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PLANTUML_HEALTHCHECK_SOURCE = "@startuml\nA->B\n@enduml";

    private final DataSource dataSource;
    private final RestClient plantUmlClient;
    private final RestClient wordpressProvisioningClient;
    private final RestClient penpotClient;
    private final SystemSettingService systemSettingService;
    private final AppSettingService appSettingService;
    private final LetsBlogServiceStatusService letsBlogServiceStatusService;
    private final RabbitMqQueueStatusService rabbitMqQueueStatusService;
    private final String plantUmlBaseUrl;
    private final String wordpressProvisionBaseUrl;
    private final String penpotBaseUrl;
    /**
     * OLLAMAのbaseUrlはAppSettingService経由でDB優先に実行時解決される(#1086)ため、他の外部連携と
     * 異なりコンストラクタ時点でRestClientを固定できない。チェックの都度、解決済みbaseUrlから
     * RestClient.Builderを組み立てるための差し替え可能な生成関数として保持する(テストでは
     * MockRestServiceServerに束縛済みのBuilderを返す関数に差し替える)。
     */
    private final Function<String, RestClient.Builder> ollamaClientBuilderFactory;
    /**
     * ComfyUIのbaseUrlもOllamaと同じくDB(AppSettingService)で実行時に解決される(issue #1567)ため、
     * チェックの都度、解決済みbaseUrlからRestClient.Builderを組み立てる。
     */
    private final Function<String, RestClient.Builder> comfyUiClientBuilderFactory;

    @Autowired
    public ConnectedServiceStatusService(
            DataSource dataSource,
            @Value("${app.plantuml-base-url}") String plantUmlBaseUrl,
            @Value("${app.wordpress-provision-base-url}") String wordpressProvisionBaseUrl,
            @Value("${app.penpot-base-url}") String penpotBaseUrl,
            SystemSettingService systemSettingService,
            AppSettingService appSettingService,
            LetsBlogServiceStatusService letsBlogServiceStatusService,
            RabbitMqQueueStatusService rabbitMqQueueStatusService) {
        this(dataSource,
                ConnectedServiceStatusService::builderWithTimeout,
                builderWithTimeout(plantUmlBaseUrl), plantUmlBaseUrl,
                builderWithTimeout(wordpressProvisionBaseUrl), wordpressProvisionBaseUrl,
                builderWithTimeout(penpotBaseUrl), penpotBaseUrl,
                systemSettingService, appSettingService,
                letsBlogServiceStatusService, rabbitMqQueueStatusService,
                ConnectedServiceStatusService::builderWithTimeout);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    ConnectedServiceStatusService(
            DataSource dataSource,
            Function<String, RestClient.Builder> comfyUiClientBuilderFactory,
            RestClient.Builder plantUmlBuilder, String plantUmlBaseUrl,
            RestClient.Builder wordpressBuilder, String wordpressProvisionBaseUrl,
            RestClient.Builder penpotBuilder, String penpotBaseUrl,
            SystemSettingService systemSettingService,
            AppSettingService appSettingService,
            LetsBlogServiceStatusService letsBlogServiceStatusService,
            RabbitMqQueueStatusService rabbitMqQueueStatusService,
            Function<String, RestClient.Builder> ollamaClientBuilderFactory) {
        this.dataSource = dataSource;
        this.plantUmlClient = plantUmlBuilder.build();
        this.wordpressProvisioningClient = wordpressBuilder.build();
        this.penpotClient = penpotBuilder.build();
        this.systemSettingService = systemSettingService;
        this.appSettingService = appSettingService;
        this.letsBlogServiceStatusService = letsBlogServiceStatusService;
        this.rabbitMqQueueStatusService = rabbitMqQueueStatusService;
        this.comfyUiClientBuilderFactory = comfyUiClientBuilderFactory;
        this.plantUmlBaseUrl = plantUmlBaseUrl;
        this.wordpressProvisionBaseUrl = wordpressProvisionBaseUrl;
        this.penpotBaseUrl = penpotBaseUrl;
        this.ollamaClientBuilderFactory = ollamaClientBuilderFactory;
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

        List<ConnectedServiceStatusDetailResponse> result = new ArrayList<>(List.of(
                runTimed("database", "データベース", this::checkDatabase),
                runTimed("llm", "LLM", this::checkLlm),
                comfyUi.join(),
                plantUml.join(),
                wordpressProvisioning.join(),
                penpot.join(),
                runTimed("brave-search", "Brave Search API", this::checkBraveSearch),
                // RabbitMQのキュー滞留・DLQ滞留(issue #589)。DLQに残っていれば、
                // 処理されなかったイベントが確実に存在するのでERRORにする。
                runTimed("rabbitmq-queues", "RabbitMQ キュー滞留", this::checkRabbitMqQueues)));

        // Let's Blog自身の9サービス(issue #589)。外部依存だけでなく、どのサービスが
        // 落ちているかがダッシュボードで分かるようにする。
        result.addAll(letsBlogServiceStatusService.checkAll().stream()
                .map(this::toDetail)
                .toList());
        return result;
    }

    private ConnectedServiceStatusDetailResponse toDetail(LetsBlogServiceStatusService.ServiceHealth health) {
        return new ConnectedServiceStatusDetailResponse(
                health.id(), health.name(),
                health.up() ? Status.NORMAL : Status.ERROR,
                0, null, health.detail(),
                letsBlogServiceStatusService.targetUrl(), Instant.now(), health.impact(), null);
    }

    private CheckOutcome checkRabbitMqQueues() {
        RabbitMqQueueStatusService.QueueStatus status = rabbitMqQueueStatusService.check();
        if (status.ok()) {
            return CheckOutcome.normal(status.targetUrl());
        }
        return new CheckOutcome(
                status.warning() ? Status.WARNING : Status.ERROR, null, status.message(), status.targetUrl());
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
                outcome.httpStatus(), outcome.errorMessage(), outcome.targetUrl(), Instant.now(), null,
                outcome.computeDevice());
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
     * 実効プロバイダー(DB優先で解決される{@link AppSettingService#getLlmProvider()}、issue #1087)
     * によって判定方法を切り替える。OLLAMAは自ホスト上のコンテナで無料のため実際に疎通確認する
     * (ComfyUIと同じ方針)。OPENAI/CLAUDEは第三者の有料APIのため実リクエストは送らない。APIキーは
     * プロジェクト単位だけでシステム全体の「設定の有無」が無いため(issue #1568)、システムの連携状況としては
     * 警告にせず正常とする(キーの設定状況はプロジェクトのAI・アセットで確認する)。
     */
    private CheckOutcome checkLlm() {
        AiProvider provider = appSettingService.getLlmProvider();
        return switch (provider) {
            case OLLAMA -> checkLlmOllama();
            case CLAUDE, OPENAI -> CheckOutcome.normal(null);
        };
    }

    /** OLLAMA専用の接続先(issue #1086)へOpenAI互換のモデル一覧エンドポイントで疎通確認する。 */
    private CheckOutcome checkLlmOllama() {
        String baseUrl = appSettingService.getLlmOllamaBaseUrl();
        RestClient client = ollamaClientBuilderFactory.apply(baseUrl).build();
        CheckOutcome outcome = checkHttpService(client, baseUrl, "/models");
        if (outcome.status() != Status.NORMAL) {
            return outcome;
        }
        return outcome.withComputeDevice(resolveOllamaComputeDevice(baseUrl));
    }

    /**
     * Ollama固有API({@code GET /api/ps})でロード中モデルの配置から演算デバイスを解決する(issue #1397)。
     * {@code /api/ps}はOpenAI互換の{@code /v1}の外にあるため、ベースURLから{@code /v1}を除いて呼ぶ。
     * 付加情報なので、取得・解釈に失敗しても例外にせず{@code null}を返し、状態判定へは影響させない。
     */
    private String resolveOllamaComputeDevice(String baseUrl) {
        String root = baseUrl.replaceFirst("/+$", "").replaceFirst("/v1$", "");
        try {
            RestClient client = ollamaClientBuilderFactory.apply(root).build();
            return ollamaComputeDeviceOf(client.get().uri("/api/ps").retrieve().body(String.class));
        } catch (RestClientException e) {
            return null;
        }
    }

    /**
     * {@code /api/ps}の応答からデバイスを決める。ロード中モデルのsize(総量)とsize_vram(VRAM上の量)を
     * 合算し、VRAM上が0ならcpu、全量ならgpu、その間ならgpu+cpu。ロード中のモデルが無ければ
     * 判別できないためunknown。本文が解釈できなければnull。
     */
    static String ollamaComputeDeviceOf(String body) {
        JsonNode root = parseJson(body);
        if (root == null) {
            return null;
        }
        long size = 0;
        long vram = 0;
        for (JsonNode model : root.path("models")) {
            size += model.path("size").asLong(0);
            vram += model.path("size_vram").asLong(0);
        }
        if (size <= 0) {
            return "unknown";
        }
        if (vram <= 0) {
            return "cpu";
        }
        return vram >= size ? "gpu" : "gpu+cpu";
    }

    /** {@code /system_stats}の{@code devices[0].type}。無い・解釈できない場合はnull。 */
    static String comfyUiComputeDeviceOf(String body) {
        JsonNode root = parseJson(body);
        if (root == null) {
            return null;
        }
        String type = root.path("devices").path(0).path("type").asText("").trim();
        return type.isEmpty() ? null : type;
    }

    private static JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /**
     * ComfyUI公式の軽量なシステム状態エンドポイントで判定する。同じ応答の本文から演算デバイスも読む
     * (issue #1397)ため、HTTPリクエスト数は増えない。
     */
    private CheckOutcome checkComfyUi() {
        String baseUrl = appSettingService.getComfyUiBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return new CheckOutcome(Status.WARNING, null,
                    "ComfyUIの接続先が設定されていません(システム設定またはプロジェクト設定で設定してください)", null);
        }
        return checkHttpService(comfyUiClientBuilderFactory.apply(baseUrl).build(), baseUrl, "/system_stats",
                ConnectedServiceStatusService::comfyUiComputeDeviceOf);
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
        return checkHttpService(client, baseUrl, path, null);
    }

    /**
     * {@code deviceResolver}が指定されたときだけ応答本文を読み、演算デバイスを解決する(issue #1397)。
     * リゾルバは例外を投げない前提で、解決結果は状態判定に使わない。
     */
    private CheckOutcome checkHttpService(
            RestClient client, String baseUrl, String path, Function<String, String> deviceResolver) {
        String targetUrl = baseUrl + path;
        try {
            if (deviceResolver == null) {
                ResponseEntity<Void> response = client.get().uri(path).retrieve().toBodilessEntity();
                return new CheckOutcome(Status.NORMAL, response.getStatusCode().value(), null, targetUrl);
            }
            ResponseEntity<String> response = client.get().uri(path).retrieve().toEntity(String.class);
            return new CheckOutcome(Status.NORMAL, response.getStatusCode().value(), null, targetUrl,
                    deviceResolver.apply(response.getBody()));
        } catch (RestClientResponseException e) {
            Status status = e.getStatusCode().is5xxServerError() ? Status.WARNING : Status.NORMAL;
            return new CheckOutcome(status, e.getStatusCode().value(), e.getMessage(), targetUrl);
        } catch (RestClientException e) {
            return new CheckOutcome(Status.ERROR, null, e.getMessage(), targetUrl);
        }
    }

    /**
     * Brave Search APIは第三者の有料APIのため、疎通確認のために定期的に実リクエストを送ることはせず、
     * APIキーが設定されているかどうかを稼働状況の代わりとして扱う。SystemSettingServiceへの本呼び出しは
     * サービス間の内部呼び出しに相当する認証コンテキストを持たない定期バッチ処理のため、認可チェック
     * 無しの{@link SystemSettingService#getBraveSearchApiKeyStatusInternal()}を使う(同メソッドの
     * Javadoc参照。ユーザー向けControllerからの呼び出しではないため問題ない)。
     */
    private CheckOutcome checkBraveSearch() {
        if (systemSettingService.getBraveSearchApiKeyStatusInternal().configured()) {
            return CheckOutcome.normal(null);
        }
        return new CheckOutcome(Status.WARNING, null, "APIキーが設定されていません", null);
    }

    /** 各チェックの結果(issue #199の詳細診断用フィールドを含む)。targetUrlはHTTPを伴わないチェックではnull。 */
    private record CheckOutcome(
            Status status, Integer httpStatus, String errorMessage, String targetUrl, String computeDevice) {
        private CheckOutcome(Status status, Integer httpStatus, String errorMessage, String targetUrl) {
            this(status, httpStatus, errorMessage, targetUrl, null);
        }

        private static CheckOutcome normal(String targetUrl) {
            return new CheckOutcome(Status.NORMAL, null, null, targetUrl);
        }

        private CheckOutcome withComputeDevice(String device) {
            return new CheckOutcome(status, httpStatus, errorMessage, targetUrl, device);
        }
    }
}
