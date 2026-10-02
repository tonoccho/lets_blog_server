package com.letsblog.common.client;

import com.letsblog.common.auth.ServiceTokenClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * <b>オプトイン(#1483)</b>: 本クラスはlbs-commonにあるが{@code @Component}ではなく、コンポーネント
 * スキャンされない。{@code generation_jobs}を使うサービス(media-service、log-writer)だけが
 * {@code @Import(GenerationJobClient.class)}で取り込む。使わないサービスにai-serviceクライアントや
 * {@code app.ai-service-uri}の要求を強制しない。#1250(2つのコンストラクタがあり
 * {@code @Autowired}無しではBean生成に失敗した)の再発を避けるため、公開コンストラクタを
 * {@code @Autowired}で明示し、テスト用コンストラクタはpackage-privateのままにしている。
 *
 * <p>操作ごとに使うサービスが違う: media-serviceは{@link #create}+{@link #updateStatus}、
 * log-writerは{@link #listRecent}のみ(Keycloakトークンを持たない)。{@link ServiceTokenClient}は
 * {@link ObjectProvider}で遅延解決し、{@link #updateStatus}を呼ぶときにだけ要求する
 * (Beanが無ければ{@code NoSuchBeanDefinitionException})。
 *
 * <p>以下は元のmedia-service側の記述。
 *
 * <p>ai-serviceの{@code POST /api/generation-jobs}・{@code PATCH /api/generation-jobs/{id}}を
 * 呼び出し、ジョブの作成・状態/結果を更新するクライアント(#573 stage2)。issue #581(C12)で
 * lbs-commonの{@link SyncServiceClient}(タイムアウト・リトライ・サーキットブレーカーの共通実装)へ
 * 移行した。方針の詳細はdocs/SYNC_SERVICE_CALLS.md参照。
 *
 * <p>GenerationJob自体はmedia-serviceへ移設していない(generation_jobsテーブルはComfyUiModelService/
 * MediaGarbageCollectionServiceに加え、AiAssistService/ArticlePlanService(LLM機能)も書き込む
 * 共有インフラのため)。所有権は#573時点ではlegacy-apiにあったが、issue #574でai-serviceへ移管された
 * ため、この呼び出し先も{@code app.legacy-api-uri}から{@code app.ai-service-uri}へ変更した。
 * ジョブの作成はlegacy-api側(呼び出し元の同期リクエスト内、例: ComfyUiModelService#startInstall)が
 * 引き続き行い(legacy-api側は自身のGenerationJobClientでai-serviceへ委譲する)、media-service側の
 * 非同期ジョブランナー(ModelInstallJobRunner)は進捗・完了・失敗の反映のみをこのクライアント経由で行う。
 *
 * <p><b>認証(issue #1083で変更)</b>: {@link #create}はジョブを起動した同期リクエストの延長で
 * 呼ばれるため、引き続き呼び出し元ユーザーのBearerトークンをそのまま転送する
 * ({@link ServiceAuthHeaders#forwardedBearer(String)})。
 *
 * <p>一方{@link #updateStatus}は{@code @Async}な非同期ジョブランナー(ModelInstallJobRunner/
 * MediaGarbageCollectionJobRunner)から、ジョブ開始から数分〜数十分後に呼ばれうる。以前は
 * ここも起動時点のユーザーBearerトークンを引き回していたが、Keycloakの{@code accessTokenLifespan}
 * (既定300秒)より長くかかるチェックポイントダウンロードでは、通知の時点でトークンが既に失効している
 * ことが常態化していた(#1083)。ai-serviceの{@code InternalGenerationJobController}は
 * 「有効なJWTさえあれば認可する」(呼び出し元ユーザーの権限を見ない)ため、ユーザーコンテキストは
 * そもそも不要であり、{@link ServiceAuthHeaders#clientCredentials}でこのサービス自身の
 * {@link ServiceTokenClient}トークン(呼び出しのたびに残り寿命を見て自動更新される)を使う。
 * これによりバックグラウンドスレッドの生存期間がどれだけ長くても認証が失効しない。
 *
 * <p><b>フォールバック方針(issue #1083で変更)</b>: {@link #create}は引き続きジョブID無しでは
 * 非同期処理を開始できないためベストエフォートで扱わず、失敗を{@link GenerationJobBridgeException}
 * として呼び出し元へ伝播させる(明確なエラー)。{@link #updateStatus}は通知内容の性質で方針を分ける。
 * <ul>
 *   <li>進捗更新("running"): 引き続き機能縮退(1回試して失敗したらログ警告のみ、フロントは
 *       古い進捗のまま表示され続けるのみで実害が小さい)。ただし認証エラー(401/403)が連続する間、
 *       0.5秒間隔の進捗報告のたびにWARNログを出し続けると無制限のログ洪水になる(#1083)ため、
     *   認証エラーのWARNログは{@link #AUTH_WARN_SUPPRESSION_WINDOW_MS}の間隔に抑制する。</li>
 *   <li>終端通知("done"/"failed"): 黙って握りつぶすと、ダウンロード自体は成功/失敗しているのに
 *       DB上のジョブが永久にrunningのまま残る(#1083の症状そのもの)。下流の一時失敗を
 *       {@link #TERMINAL_UPDATE_MAX_ATTEMPTS}回まで再試行し、それでも失敗したらERRORとして
 *       記録する(呼び出し元へは例外を伝播させない。それ以上の解消は、生きたままrunningで
 *       取り残されたジョブをai-service側で検出・解消するタイムアウト機構に委ねる)。</li>
 * </ul>
 */
public class GenerationJobClient {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobClient.class);

    /** ジョブの終端状態。この状態への更新だけを再試行対象にする。 */
    private static final Set<String> TERMINAL_STATUSES = Set.of("done", "failed");

    private static final int TERMINAL_UPDATE_MAX_ATTEMPTS = 3;
    private static final long[] TERMINAL_RETRY_BACKOFF_MS = {500L, 1000L};

    /** この間隔より短い間に認証エラーのWARNログを重複して出さない(#1083、無制限ログ洪水対策)。 */
    private static final long AUTH_WARN_SUPPRESSION_WINDOW_MS = 30_000L;

    private final SyncServiceClient client;
    private final Supplier<ServiceTokenClient> serviceTokenClient;

    private volatile long lastAuthWarnLoggedAtMs = 0L;

    @Autowired
    public GenerationJobClient(
            RestClient.Builder builder,
            @Value("${app.ai-service-uri}") String aiServiceUri,
            ObjectProvider<ServiceTokenClient> serviceTokenClient) {
        this(builder, aiServiceUri, serviceTokenClient::getObject, null);
    }

    /**
     * テスト用: {@code SyncServiceClient}のサーキットブレーカーレジストリを指定できる版
     * ({@link SyncServiceClient.Builder#circuitBreakerRegistry}と同じ理由)。既定の
     * レジストリはJVM全体で"ai-service"名を共有するため、同一クラス内の複数テストが
     * 実際にHTTP呼び出しを行う場合、あるテストの5xx応答が別テストのサーキットブレーカー
     * 状態に漏れる。本番コードから呼ぶ必要は無い。{@code circuitBreakerRegistry}が
     * {@code null}なら既定のレジストリ(JVM全体で共有)を使う。
     */
    GenerationJobClient(
            RestClient.Builder builder,
            String aiServiceUri,
            ServiceTokenClient serviceTokenClient,
            io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry) {
        this(builder, aiServiceUri, () -> serviceTokenClient, circuitBreakerRegistry);
    }

    private GenerationJobClient(
            RestClient.Builder builder,
            String aiServiceUri,
            Supplier<ServiceTokenClient> serviceTokenClient,
            io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry) {
        SyncServiceClient.Builder syncClientBuilder =
                SyncServiceClient.builder(builder, "ai-service", aiServiceUri).profile(SyncCallProfile.SHORT);
        if (circuitBreakerRegistry != null) {
            syncClientBuilder.circuitBreakerRegistry(circuitBreakerRegistry);
        }
        this.client = syncClientBuilder.build();
        this.serviceTokenClient = serviceTokenClient;
    }

    /**
     * ジョブを作成する(#573 stage3、legacy-apiに残らなくなったコントローラからの起動用)。
     *
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダーの値(例: {@code "Bearer xxx"}）。
     */
    public GenerationJobSummary create(String type, String requestPayload, String bearerToken) {
        try {
            GenerationJobSummary created = client.post(
                    "/api/internal/ai/generation-jobs", new Object[0],
                    Map.of("type", type, "requestPayload", requestPayload == null ? "" : requestPayload),
                    GenerationJobSummary.class, ServiceAuthHeaders.forwardedBearer(bearerToken));
            if (created == null) {
                throw new GenerationJobBridgeException("ai-serviceから空の応答を受け取りました", null);
            }
            return created;
        } catch (SyncServiceException e) {
            throw new GenerationJobBridgeException("ai-serviceの/api/generation-jobs作成呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 直近のジョブ一覧を取得する(log-writerの統合操作ログ、#572、#825)。
     *
     * <p>問い合わせ先はai-serviceの{@code GET /api/generation-jobs}(#825: #572の時点ではlegacy-apiが
     * 所有していたが、AIサービス抽出でai-serviceへ移設され、取り残されたクライアントが404を受け続けて
     * 統合ログAPIが常に502になっていた)。ai-serviceのSecurityConfigは有効なBearer JWTの無い
     * リクエストを一律401にする(ADR-0008)ため、呼び出し元(統合ログAPIの実際の利用者)の
     * Bearerトークンをそのまま転送する(IdentityClientと同じ理由・パターン)。
     *
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダーの値(例: {@code "Bearer xxx"}）。
     *                    nullの場合はヘッダーを付与せずに呼び出す(ai-service側で401になる)。
     */
    public List<GenerationJobSummary> listRecent(String bearerToken) {
        try {
            List<GenerationJobSummary> jobs = client.get(
                    "/api/generation-jobs", new Object[0],
                    new ParameterizedTypeReference<List<GenerationJobSummary>>() { },
                    ServiceAuthHeaders.forwardedBearer(bearerToken));
            return jobs != null ? jobs : List.of();
        } catch (SyncServiceException e) {
            // 原因(SyncServiceExceptionのメッセージ。"[ai-service] GET /api/generation-jobs: <詳細>")を
            // 必ず連結する。#825の縮退で失敗がHTTPレスポンスに出なくなったため、ログに原因が
            // 残らないと404(向き先ミス)・401(realm/audience不整合)・タイムアウト・
            // サーキットオープンのどれなのかを切り分けられない。
            throw new GenerationJobBridgeException(
                    "ai-serviceの/api/generation-jobs呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * ジョブの進捗・完了・失敗を反映する(#573 stage2)。認証・フォールバック方針はクラスのjavadoc参照
     * (issue #1083で呼び出し元Bearerトークンの転送をやめ、サービス自身のトークンに切り替えた)。
     */
    public void updateStatus(Long jobId, String status, String resultPayload) {
        if (TERMINAL_STATUSES.contains(status)) {
            updateTerminalStatus(jobId, status, resultPayload);
        } else {
            updateProgress(jobId, status, resultPayload);
        }
    }

    private void updateProgress(Long jobId, String status, String resultPayload) {
        try {
            patch(jobId, status, resultPayload);
        } catch (SyncServiceException e) {
            logProgressUpdateFailure(jobId, status, e);
        }
    }

    private void updateTerminalStatus(Long jobId, String status, String resultPayload) {
        SyncServiceException lastFailure = null;
        for (int attempt = 1; attempt <= TERMINAL_UPDATE_MAX_ATTEMPTS; attempt++) {
            try {
                patch(jobId, status, resultPayload);
                return;
            } catch (SyncServiceException e) {
                lastFailure = e;
                if (attempt < TERMINAL_UPDATE_MAX_ATTEMPTS) {
                    sleep(TERMINAL_RETRY_BACKOFF_MS[attempt - 1]);
                }
            }
        }
        // #1083: 終端通知を握りつぶすと、ダウンロード自体は成功/失敗しているのにDB上のジョブが
        // 永久にrunningのまま残る。再試行を使い切ってもここでは例外を投げず(呼び出し元の
        // ジョブランナーを異常終了させない)、ERRORとして記録するに留める。それでも運悪く残った
        // runningジョブの解消は、ai-service側のタイムアウト機構(#1083要件4)に委ねる。
        log.error(
                "ai-serviceの/api/internal/ai/generation-jobs/{}への終端状態({})反映に{}回失敗しました。"
                        + "この通知は失われ、ジョブがDB上running状態のまま取り残される可能性があります: {}",
                jobId, status, TERMINAL_UPDATE_MAX_ATTEMPTS,
                lastFailure == null ? null : lastFailure.getMessage(), lastFailure);
    }

    private void patch(Long jobId, String status, String resultPayload) {
        client.patch(
                "/api/internal/ai/generation-jobs/{id}", new Object[] {jobId},
                Map.of("status", status, "resultPayload", resultPayload == null ? "" : resultPayload),
                ServiceAuthHeaders.clientCredentials(serviceTokenClient.get()));
    }

    private void logProgressUpdateFailure(Long jobId, String status, SyncServiceException e) {
        if (isAuthFailure(e) && !authWarnDue()) {
            return;
        }
        log.warn(
                "ai-serviceの/api/internal/ai/generation-jobs/{}呼び出しに失敗しました(status={}): {}",
                jobId, status, e.getMessage());
    }

    /**
     * @return 認証エラーのWARNログを今出してよければtrue(かつ最終出力時刻を更新する)。
     *         {@link #AUTH_WARN_SUPPRESSION_WINDOW_MS}以内の再呼び出しはfalseを返し、
     *         0.5秒間隔の進捗報告のたびに際限なくWARNが出続けること(#1083)を防ぐ。
     */
    private boolean authWarnDue() {
        long now = System.currentTimeMillis();
        if (now - lastAuthWarnLoggedAtMs < AUTH_WARN_SUPPRESSION_WINDOW_MS) {
            return false;
        }
        lastAuthWarnLoggedAtMs = now;
        return true;
    }

    private static boolean isAuthFailure(SyncServiceException e) {
        return e instanceof SyncServiceClientErrorException clientError
                && (clientError.statusCode() == 401 || clientError.statusCode() == 403);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
