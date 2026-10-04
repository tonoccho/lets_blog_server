package com.letsblog.gateway.config;

import com.nimbusds.jwt.JWTParser;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * レート制限(#560。旧legacy-apiのRateLimitInterceptorから移設)。バケット分類ロジックは
 * 移設元と同一だが、アップロード系エンドポイントを管理画面から動的に変更する機能
 * (旧AppSettingService経由のDB設定)は、gatewayがDBを持たない設計のため対応していない
 * (静的なデフォルト値のみ。follow-up issueで再検討する)。
 *
 * <h2>api-globalバケットの分割粒度(#749)</h2>
 *
 * <p>issue #584でWeb(BFF: Server Component/Server Action/Route Handler)の呼び先が
 * legacy-api直叩きからgateway経由へ統一された結果、従来はブラウザ発のポーリングだけが通っていた
 * このフィルタを、管理画面の全データ取得が通るようになった。api-globalが
 * 「プロセス全体で100req/分」の単一バケットのままだと、画面遷移のたびに数本〜十数本の
 * BFF呼び出しが同じ枠を食い合い、#464(バケット枯渇でサーバーが応答しなくなる)の再発に至る。
 * 実測(2026-08-29のgatewayアクセスログ24時間分)でもapi-global相当のリクエストはピーク239req/分に達し、
 * 24時間で399件の429が発生していた。
 *
 * <p>そこで api-global だけを<b>クライアント単位</b>へ分割し、さらに<b>内部(BFF)トラフィックを
 * 別バケット(api-internal)へ分離</b>する。分割キーは次の順で決まる:
 *
 * <ol>
 *   <li>{@code X-Forwarded-For} がある = reverse-proxy(nginx)を経由した<b>外部</b>リクエスト。
 *       キーは同ヘッダの<b>末尾</b>の値({@code ip:<addr>})。nginxは
 *       {@code $proxy_add_x_forwarded_for} で自身が観測したpeerアドレスを末尾に<b>追記</b>するため、
 *       クライアントが偽装ヘッダを送っても末尾の値は詐称できない(先頭の値は詐称できるので使わない)。
 *       バケットは api-global。</li>
 *   <li>{@code X-Forwarded-For} が無い = lbs-net内部からgatewayを直接叩いた<b>内部</b>リクエスト
 *       (webコンテナのBFF等)。Bearerトークンが載っていればそのJWTの {@code sub} をキーにし
 *       ({@code user:<sub>})、ログイン中ユーザーごとに枠を分ける。バケットは api-internal。</li>
 *   <li>内部かつトークン無し(proxy.tsの {@code /api/auth/setup-status} 等)は、
 *       接続元アドレスをキーにする({@code peer:<addr>})。バケットは api-internal。</li>
 * </ol>
 *
 * <p>JWTはここでは<b>検証せずに</b>パースする(このフィルタはSpring Securityのフィルタチェーンより
 * 前段の{@code Ordered.HIGHEST_PRECEDENCE + 1}で動くため、検証済みJwtはまだ利用できない)。
 * 署名が不正なトークンは後段のresource server設定(SecurityConfig)が401で弾くため、
 * 偽造subで得られるのは「自分専用の枠」だけで制限の回避にはならない。ただし外部リクエストに対して
 * subを使うと、乱数subの偽造トークンを撒くことで枠を無限に増やせてしまうため、
 * <b>外部は常にIP</b>で分割し、subは内部リクエストにのみ使う。
 *
 * <p>auth-endpoint / upload-endpoint / operation-log-endpoint は従来どおり<b>プロセス全体で
 * 1バケット</b>のままとする。前者2つは「総量に対する上限」(ブルートフォース耐性・
 * 画像生成やアップロードによる資源枯渇の防止)であり、クライアント単位に割ると総量が青天井になるため
 * (upload-endpointの上限値は#444で管理画面から変更可能にする対象でもある)。
 * operation-log-endpointはBFFからの書き込み専用で、そもそも300req/分の枠に余裕がある。
 *
 * <p>クライアントキーごとにRateLimiterインスタンスを保持するため、キー数の上限
 * ({@value #MAX_TRACKED_CLIENT_KEYS})を超えた分は共有のフォールバックキーへ寄せ、
 * マップの無制限な増殖を防ぐ。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitWebFilter implements WebFilter {

    private static final String UPLOAD_ENDPOINT = "upload-endpoint";
    private static final String OPERATION_LOG_ENDPOINT = "operation-log-endpoint";
    private static final String AUTH_ENDPOINT = "auth-endpoint";
    private static final String API_GLOBAL = "api-global";
    private static final String API_INTERNAL = "api-internal";

    private static final String OPERATION_LOG_PATH = "/api/operation-logs";

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String KEY_SEPARATOR = "|";
    /** キー数が上限に達した後の新規クライアントがまとめて使う共有キー。 */
    private static final String OVERFLOW_CLIENT_KEY = "overflow";
    /** 保持するクライアント別RateLimiterの上限数。 */
    private static final int MAX_TRACKED_CLIENT_KEYS = 10_000;

    private static final Set<String> AUTH_STATUS_CHECK_PATHS =
            Set.of("/api/auth/setup-status", "/api/auth/totp/status");

    /**
     * NextAuthのクライアントサイド診断ロガーの送信先(issue #781)。ログイン試行ではないため
     * {@link #AUTH_ENDPOINT}(5 req/60s、プロセス全体で1バケット)を消費させない。
     *
     * <p>本来これはnginxのNextAuth用locationでwebへ振り分けられ、gatewayには到達しない
     * (#781でそちらも修正した)。ただしnginxを経由しない経路や設定の取りこぼしで到達した場合に、
     * ブラウザのログノイズだけで<b>全ユーザーの</b>ログイン試行が429で弾かれる自己DoSになるため、
     * gateway側でも保険をかける。24時間の実測では77件が到達し、うち10件が429だった。
     */
    private static final String NEXTAUTH_CLIENT_LOG_PATH = "/api/auth/_log";

    /**
     * upload-endpointバケットに入れる、実アップロード・実生成のパス(issue #999)。
     *
     * <p>#999より前は「{@code /upload}または{@code /image}を含むパスはupload-endpoint、
     * ただし{@code LIGHTWEIGHT_IMAGE_METADATA_PATH_SUFFIXES}に載っている3件は除く」という
     * <b>ブロックリスト</b>方式だった。{@code /image}を含む新しい軽量な画像関連メタデータAPI
     * ({@code GET /api/projects/{id}/image-settings}等)が増えるたびに、例外リストへ
     * 追加し忘れて誤ってupload-endpoint(プロセス全体で10req/時)を消費する事故を
     * 繰り返した(#999)。
     *
     * <p>今は逆に、資源枯渇の防止という本来の目的(クラスJavadoc参照)に直接該当する
     * 「実際に重い操作」だけを明示的に列挙する<b>許可リスト</b>方式にしている。新しい
     * 軽量な画像関連メタデータAPIが増えても、ここに追加しない限り自動的にapi-globalへ
     * 入るため、同種の事故が原理的に起きない。
     *
     * <ul>
     *   <li>{@code POST /api/media/upload} — 実際の画像/メディアバイナリのアップロード</li>
     *   <li>{@code POST /api/generated-images/upload} — 利用者が手元の画像を生成画像ギャラリーへ
     *       アップロードする(issue #1599、multipart最大20MB。デコード・リサイズを行う重い処理)。
     *       {@code /api/generated-images}配下の他のAPI(一覧・詳細・タグ等の軽量なメタデータ)を
     *       巻き込まないよう、完全一致で扱う</li>
     *   <li>{@code POST /api/ai/image} — 実際の画像生成(ComfyUI/ChatGPT呼び出し)。
     *       {@code /api/ai/image-options}(設定の参照)を巻き込まないよう、部分一致ではなく
     *       完全一致で扱う</li>
     *   <li>{@code POST /api/projects/{id}/asset-images/{generatedImageId}/upload} —
     *       生成済み画像を各環境へ実際にアップロードする
     *       ({@code BulkManagementController#uploadAssetImage}参照)</li>
     *   <li>{@code POST /api/projects/{id}/bulk-management/upload} — プラグイン/テーマ/CSV等の
     *       ファイルを各環境へ実際にmultipartアップロードし一括適用する
     *       ({@code BulkManagementController#runBulkOperationUpload}参照)。画像アップロード
     *       ではないが実際の重いファイルアップロードであるため、#999でも
     *       upload-endpointに残す判断をした</li>
     * </ul>
     *
     * <p><b>{@code POST /api/users/{id}/avatar}(issue #1241、プロフィール編集画面のアバター
     * アップロード、最大20MB)は意図的にここへ含めない。</b>実バイナリのアップロードという点は
     * 上の件と同じだが、upload-endpointは<b>プロセス全体で1バケット</b>(クラスJavadoc参照)
     * であり、他ユーザーの操作(記事の画像アップロード等)がこの共有枠を消費していると、
     * 自分のアバター変更が横から巻き込まれて429になる。これはまさに本Issueが要件5の
     * カッコ書きで名指しした#999の実害パターンであり、許可リストに機械的に追加することは
     * その再発を意味する。アバター変更は「総量に対する上限」で守るべき資源枯渇のリスクが
     * 低い(ユーザーが自分の意思で行う低頻度な個人操作であり、他ユーザーの操作から
     * 隔離されているべき)ため、api-global(クライアント単位に分割されたバケット。
     * クラスJavadoc参照)に委ねる。</p>
     *
     * <p><b>{@code POST /api/ai/image/jobs}(issue #1405、画像生成を非同期ジョブとして受理する口)
     * も意図的にここへ含めない。</b>同期の{@code /api/ai/image}は1リクエストが生成の完了まで
     * GPUを占有するため共有枠で総量を絞っている。一方こちらは受理がジョブ1件の作成で終わり、
     * GPUの占有は専用Executor(media-serviceの{@code imageGenerationExecutor}、並列度1・待ち行列10件で
     * 満杯なら受理側がジョブをfailedにする)が直列化して有限に抑える。ここを共有枠に入れると、
     * プロセス全体で1時間に10回という枠を非同期の受理が消費し、同じ1時間に走る他ユーザーの
     * 生成・アップロードを巻き添えで429にする(#999、上のavatarと同じ実害パターン)ため、
     * クライアント単位のapi-globalに委ねる。同期経路と非同期経路の枠が別になる点は
     * 受け入れた上での判断である。</p>
     *
     * <p>{@code apps/web/e2e/support/endpoints.ts#isUploadBucketPath}に全く同じ定義を
     * 持つ(二重管理)。両者が食い違っていないことは
     * {@code RateLimitUploadBucketSyncTest}が検証している(#999 受入基準4)。
     */
    private static final Set<String> UPLOAD_BUCKET_EXACT_PATHS = Set.of(
            "/api/media/upload",
            "/api/generated-images/upload",
            "/api/ai/image");

    private static final Pattern ASSET_IMAGE_UPLOAD_PATH_PATTERN =
            Pattern.compile("^/api/projects/[^/]+/asset-images/[^/]+/upload$");

    private static final Pattern BULK_MANAGEMENT_UPLOAD_PATH_PATTERN =
            Pattern.compile("^/api/projects/[^/]+/bulk-management/upload$");

    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private final RateLimitProperties properties;

    public RateLimitWebFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    /** レート制限の適用単位。{@code internal}はlbs-net内部から直接gatewayを叩いた呼び出し。 */
    private record ClientIdentity(boolean internal, String key) {
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        String bucketName = getRateLimiterName(path);
        String limiterKey = bucketName;
        if (API_GLOBAL.equals(bucketName)) {
            ClientIdentity client = resolveClient(request);
            bucketName = client.internal() ? API_INTERNAL : API_GLOBAL;
            limiterKey = bucketName + KEY_SEPARATOR + client.key();
        }
        RateLimiter rateLimiter = resolveRateLimiter(bucketName, limiterKey);

        if (!rateLimiter.acquirePermission()) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            response.getHeaders().set("Retry-After", "60");
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = "{\"error\":\"Rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        }

        return chain.filter(exchange);
    }

    /**
     * バケットとクライアントキーの組に対応するRateLimiterを返す。
     * 追跡中のキー数が上限に達している場合は、新規クライアントを共有のフォールバックキーへ寄せる。
     */
    private RateLimiter resolveRateLimiter(String bucketName, String limiterKey) {
        RateLimiter existing = rateLimiters.get(limiterKey);
        if (existing != null) {
            return existing;
        }
        String effectiveKey = limiterKey;
        if (rateLimiters.size() >= MAX_TRACKED_CLIENT_KEYS) {
            effectiveKey = bucketName + KEY_SEPARATOR + OVERFLOW_CLIENT_KEY;
        }
        return rateLimiters.computeIfAbsent(effectiveKey, key -> createRateLimiter(key, bucketName));
    }

    private RateLimiter createRateLimiter(String limiterKey, String bucketName) {
        RateLimitProperties.Bucket bucket = switch (bucketName) {
            case UPLOAD_ENDPOINT -> properties.getUploadEndpoint();
            case OPERATION_LOG_ENDPOINT -> properties.getOperationLogEndpoint();
            case AUTH_ENDPOINT -> properties.getAuthEndpoint();
            case API_INTERNAL -> properties.getApiInternal();
            default -> properties.getApiGlobal();
        };
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(bucket.getLimitForPeriod())
                .limitRefreshPeriod(bucket.getLimitRefreshPeriod())
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiter.of(limiterKey, config);
    }

    /** api-globalの分割キー(クラスJavadocの表を参照)。 */
    private ClientIdentity resolveClient(ServerHttpRequest request) {
        String externalIp = externalClientIp(request);
        if (externalIp != null) {
            return new ClientIdentity(false, "ip:" + externalIp);
        }
        String subject = unverifiedJwtSubject(request);
        if (subject != null) {
            return new ClientIdentity(true, "user:" + subject);
        }
        return new ClientIdentity(true, "peer:" + peerAddress(request));
    }

    /**
     * reverse-proxy(nginx)が付与した X-Forwarded-For の末尾の値を返す(無ければnull=内部リクエスト)。
     * nginxの{@code $proxy_add_x_forwarded_for}はクライアント申告値の後ろに自身が観測したpeerアドレスを
     * 追記するため、末尾の値だけが信頼できる。
     */
    private String externalClientIp(ServerHttpRequest request) {
        List<String> headerValues = request.getHeaders().get(FORWARDED_FOR_HEADER);
        if (headerValues == null) {
            return null;
        }
        String lastValue = null;
        for (String headerValue : headerValues) {
            for (String candidate : headerValue.split(",")) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty()) {
                    lastValue = trimmed;
                }
            }
        }
        return lastValue;
    }

    /**
     * Authorizationヘッダのトークンから{@code sub}クレームを取り出す(署名検証はしない。
     * 検証は後段のresource server設定が行う。クラスJavadoc参照)。
     */
    private String unverifiedJwtSubject(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            String subject = JWTParser.parse(token).getJWTClaimsSet().getSubject();
            if (subject == null || subject.isBlank()) {
                return null;
            }
            return subject;
        } catch (Exception e) {
            // パースできないトークンはクライアント識別に使えないだけで、拒否は後段(401)に委ねる。
            return null;
        }
    }

    private String peerAddress(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    private String getRateLimiterName(String requestPath) {
        if (requestPath.startsWith(OPERATION_LOG_PATH)) {
            return OPERATION_LOG_ENDPOINT;
        } else if (AUTH_STATUS_CHECK_PATHS.contains(requestPath)
                || NEXTAUTH_CLIENT_LOG_PATH.equals(requestPath)) {
            return API_GLOBAL;
        } else if (requestPath.contains("/auth/")
                || requestPath.contains("/login")
                || requestPath.contains("/register")) {
            return AUTH_ENDPOINT;
        } else if (isUploadBucketPath(requestPath)) {
            return UPLOAD_ENDPOINT;
        }
        return API_GLOBAL;
    }

    /** {@link #UPLOAD_BUCKET_EXACT_PATHS}のJavadoc参照。 */
    private boolean isUploadBucketPath(String requestPath) {
        return UPLOAD_BUCKET_EXACT_PATHS.contains(requestPath)
                || ASSET_IMAGE_UPLOAD_PATH_PATTERN.matcher(requestPath).matches()
                || BULK_MANAGEMENT_UPLOAD_PATH_PATTERN.matcher(requestPath).matches();
    }
}
