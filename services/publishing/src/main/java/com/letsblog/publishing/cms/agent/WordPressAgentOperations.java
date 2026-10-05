package com.letsblog.publishing.cms.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.cms.AuthCookie;
import com.letsblog.publishing.cms.AuthorProvisioningRequest;
import com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.publishing.cms.CmsMediaReferenceScan;
import com.letsblog.publishing.cms.CmsMediaSummary;
import com.letsblog.publishing.cms.CmsPostContentSummary;
import com.letsblog.publishing.cms.CmsPostSummary;
import com.letsblog.publishing.cms.ConnectionCheckResult;
import com.letsblog.publishing.cms.LetsblogPluginStatus;
import com.letsblog.publishing.cms.LetsblogSnsCommand;
import com.letsblog.publishing.cms.SignedPreview;
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.cms.PostContent;
import com.letsblog.publishing.cms.PostResult;
import com.letsblog.publishing.cms.ReferencePost;
import com.letsblog.publishing.cms.WpCliInstallResult;
import com.letsblog.publishing.config.LegacyJacksonRestClientConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 常駐wordpressコンテナ内の内部限定プロビジョニングエージェント(infra/wordpress/provision-agent)
 * の`/wp-cli/*`エンドポイント経由で、自動構築(managed)WordPressサイトをwp-cliで操作する。
 * 外部SSHサイト向けのWordPressSshOperationsと同じ役割を、SSHの代わりにエージェントへのHTTP呼び出しで担う。
 */
@Component
@Slf4j
public class WordPressAgentOperations {

    private final RestClient client;
    private final String provisionToken;

    @Autowired
    public WordPressAgentOperations(
            RestClient.Builder restClientBuilder,
            @Value("${app.wordpress-provision-base-url}") String baseUrl,
            @Value("${app.wordpress-provision-token}") String provisionToken,
            @Value("${app.wordpress-agent-connect-timeout-seconds}") long connectTimeoutSeconds,
            @Value("${app.wordpress-agent-read-timeout-seconds}") long readTimeoutSeconds) {
        this(restClientBuilder, baseUrl, provisionToken,
                Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(readTimeoutSeconds));
    }

    /**
     * テスト専用: タイムアウト値を{@link Duration}で直接指定して検証するためのコンストラクタ
     * (WordPressBulkManagementClientの同種のテスト専用コンストラクタと同じ位置付け)。
     */
    WordPressAgentOperations(
            RestClient.Builder restClientBuilder, String baseUrl, String provisionToken,
            Duration connectTimeout, Duration readTimeout) {
        this(restClientBuilder.clone().requestFactory(timeoutRequestFactory(connectTimeout, readTimeout)),
                baseUrl, provisionToken);
    }

    /**
     * テスト専用: リクエストファクトリ(MockRestServiceServer等)をビルダー側で差し込み済みの場合に使う。
     * タイムアウトは設定しない。
     */
    WordPressAgentOperations(RestClient.Builder restClientBuilder, String baseUrl, String provisionToken) {
        RestClient.Builder clonedBuilder = restClientBuilder.clone().baseUrl(baseUrl);
        LegacyJacksonRestClientConfig.preferJackson2(clonedBuilder);
        this.client = clonedBuilder.build();
        this.provisionToken = provisionToken;
    }

    private static JdkClientHttpRequestFactory timeoutRequestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }

    private static boolean isTimeout(ResourceAccessException e) {
        return e.getCause() instanceof HttpTimeoutException || e.getCause() instanceof SocketTimeoutException;
    }

    /** タイムアウトと、接続拒否などその他の接続失敗とで文言を分ける(エラー応答はRestClientResponseException側)。 */
    private static String accessFailureLabel(ResourceAccessException e) {
        return isTimeout(e) ? "エージェントへの接続がタイムアウトしました" : "エージェントへの接続に失敗しました";
    }

    private static String accessFailureMessage(ResourceAccessException e) {
        return accessFailureLabel(e) + ": " + e.getMessage();
    }

    /**
     * `wp core version`の実行結果で疎通確認する。エージェントへ到達できない、またはサイトが
     * 見つからない場合と、wp-cliコマンド自体が失敗した場合とで、failureReasonの文言を分ける。
     */
    public ConnectionCheckResult testConnection(WordPressCredentials creds) {
        try {
            JsonNode body = post("/wp-cli/core-version", Map.of("slug", creds.wpSlug()));
            return ConnectionCheckResult.success(null, "wp core version: " + body.path("version").asText());
        } catch (RestClientResponseException e) {
            if (isConnectivityStage(e.getStatusCode())) {
                return ConnectionCheckResult.failure("エージェントへの接続に失敗しました: " + agentErrorDetail(e));
            }
            return ConnectionCheckResult.failure("wp core versionの実行に失敗しました: " + agentErrorDetail(e));
        } catch (ResourceAccessException e) {
            log.warn("{} (wpSlug={}): {}", accessFailureLabel(e), creds.wpSlug(), e.getMessage());
            return ConnectionCheckResult.failure(accessFailureMessage(e));
        }
    }

    /**
     * エージェント経由(wp-cliのローカル実行)は、SSH同様WordPress REST APIのロール権限という
     * 概念を経由しないため、疎通確認が成功する = 管理操作も行えるとみなす。
     */
    public boolean hasAuthorProvisioningCapability(WordPressCredentials creds) {
        return testConnection(creds).ok();
    }

    public List<String> resolveCategories(WordPressCredentials creds, List<String> names) {
        return resolveTerms(creds, "category", names);
    }

    public List<String> resolveTags(WordPressCredentials creds, List<String> names) {
        return resolveTerms(creds, "post_tag", names);
    }

    private List<String> resolveTerms(WordPressCredentials creds, String taxonomy, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode body = post("/wp-cli/resolve-terms",
                    Map.of("slug", creds.wpSlug(), "taxonomy", taxonomy, "names", names));
            List<String> ids = new ArrayList<>();
            body.path("ids").forEach(id -> ids.add(id.asText()));
            return ids;
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("カテゴリ/タグの解決に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    public String provisionAuthor(WordPressCredentials creds, AuthorProvisioningRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("slug", creds.wpSlug());
        payload.put("email", request.email());
        payload.put("wpRole", request.wpRole() != null ? request.wpRole() : "author");
        putIfPresent(payload, "firstName", request.firstName());
        putIfPresent(payload, "lastName", request.lastName());
        putIfPresent(payload, "displayName", request.displayName());
        putIfPresent(payload, "websiteUrl", request.websiteUrl());
        putIfPresent(payload, "bio", request.bio());

        try {
            JsonNode body = post("/wp-cli/provision-author", payload);
            return body.path("userId").asText();
        } catch (RestClientResponseException e) {
            throw new AgentOperationException(authorErrorMessage(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    private String authorErrorMessage(RestClientResponseException e) {
        String detail = agentErrorDetail(e);
        if (e.getStatusCode().value() == 403) {
            return "WordPress著者の作成/更新に失敗しました: サイトに登録されている認証情報のWordPress"
                    + "アカウントにユーザー作成・更新権限(Administrator)がない可能性があります。(詳細: " + detail + ")";
        }
        return "WordPress著者の作成/更新に失敗しました: " + detail;
    }

    public PostResult createOrUpdatePost(WordPressCredentials creds, PostContent content, String existingPostId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("slug", creds.wpSlug());
        payload.put("title", content.title());
        payload.put("status", content.status());
        if (content.publishScheduledAt() != null) {
            // エージェント側が対応していれば予約投稿として扱われる(未対応の場合は無視される)。
            payload.put("publishScheduledAt", content.publishScheduledAt().toString());
        }
        payload.put("htmlContent", content.htmlContent() != null ? content.htmlContent() : "");
        if (existingPostId != null) {
            payload.put("existingPostId", existingPostId);
        }
        if (content.slug() != null && !content.slug().isBlank()) {
            payload.put("postSlug", content.slug());
        }
        if (content.categoryIds() != null) {
            // 空リストも明示的に送る(frontmatterでカテゴリを全て外した変更を反映するため。issue #467)。
            payload.put("categoryIds", content.categoryIds());
        }
        if (content.tagIds() != null) {
            // 同上(issue #467)。空リストでもタグをクリアする意図として送る。
            payload.put("tagIds", content.tagIds());
        }
        if (content.featuredMediaId() != null) {
            payload.put("featuredMediaId", content.featuredMediaId());
        }
        if (content.authorId() != null) {
            payload.put("authorId", content.authorId());
        }

        log.info("エージェント投稿リクエスト送信: slug={}, existingPostId={}, featuredMediaId={}",
                creds.wpSlug(), existingPostId, content.featuredMediaId());
        try {
            JsonNode body = post("/wp-cli/post", payload);
            PostResult postResult = new PostResult(
                    body.path("postId").asText(), body.path("guid").asText(), body.path("status").asText());
            log.info("エージェント投稿レスポンス: postId={}, status={}", postResult.id(), postResult.status());
            return postResult;
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("WordPress投稿の作成/更新に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * 指定IDの投稿がWordPress側に実在するかを判定する(読み取り専用)。
     * WordPressAdapter.postExists(issue #493)から呼ばれる。エージェントへの接続自体に失敗した
     * 場合は判定不能として安全側(true=再利用を許容)を返す。メディア(添付ファイル)も
     * post_type=attachmentのwp_postsレコードのため、`wp post get`ベースのこのエンドポイントは
     * WordPressAdapter.mediaExists(issue #495)からも同じ判定として再利用される。
     */
    public boolean postExists(WordPressCredentials creds, String postId) {
        try {
            JsonNode body = post("/wp-cli/post-exists", Map.of("slug", creds.wpSlug(), "postId", postId));
            return body.path("exists").asBoolean(true);
        } catch (RestClientResponseException | ResourceAccessException e) {
            log.warn("投稿の実在確認に失敗しました (wpSlug={}, postId={}): {}", creds.wpSlug(), postId, e.getMessage());
            return true;
        }
    }

    /** メールアドレスに一致する既存WordPressユーザーIDを検索する(作成は行わない、読み取り専用)。 */
    public java.util.Optional<String> findAuthorIdByEmail(WordPressCredentials creds, String email) {
        try {
            JsonNode body = post("/wp-cli/find-author", Map.of("slug", creds.wpSlug(), "email", email));
            String userId = body.path("userId").asText(null);
            return java.util.Optional.ofNullable(userId);
        } catch (RestClientResponseException | ResourceAccessException e) {
            log.warn("投稿者のWordPressユーザーID検索に失敗しました (wpSlug={}): {}", creds.wpSlug(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * 対象が存在しない場合(issue #1070)は、エージェント/wp-cliの疎通・実行そのものの失敗
     * ({@link AgentOperationException}、502)とは区別し、{@link PostNotFoundException}(404)を投げる。
     * provision-agent側は{@code /wp-cli/post-exists}と同じ存在確認を削除前に行い、対象なしを404で返す。
     */
    public void deletePost(WordPressCredentials creds, String postId) {
        try {
            post("/wp-cli/post-delete", Map.of("slug", creds.wpSlug(), "postId", postId));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new PostNotFoundException("投稿 '" + postId + "' が見つかりません: " + agentErrorDetail(e));
            }
            throw new AgentOperationException("WordPress投稿の削除に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * 記事プレビュー(ArticlePreviewService)のテーマCSS/DOM取得向けに、サイト内の最新公開記事を
     * 「参照記事」として返す(読み取り専用)。従来はArticlePreviewServiceが認証なしのWordPress
     * REST APIを直接叩いていたが、managedサイトは他の全操作と同じくエージェント経由のwp-cliへ揃える
     * (issue #519)。参照記事が存在しない場合は空を返す。
     */
    public java.util.Optional<ReferencePost> getLatestPost(WordPressCredentials creds) {
        try {
            JsonNode body = post("/wp-cli/reference-post", Map.of("slug", creds.wpSlug()));
            if (!body.path("found").asBoolean(false)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new ReferencePost(
                    body.path("id").asText(), body.path("link").asText(),
                    body.path("title").asText(), body.path("content").asText()));
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("参照記事の取得に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * エージェント経由で `wp letsblog status` を実行し、letsblog プラグインの導入状態を判定する
     * (issue #1557)。コマンドが失敗する(プラグインが無い・停止している)場合は未導入。
     */
    public LetsblogPluginStatus letsblogPluginStatus(WordPressCredentials creds) {
        try {
            JsonNode body = post("/wp-cli/letsblog-status", Map.of("slug", creds.wpSlug()));
            String stdout = body.path("stdout").asText("");
            String stderr = body.path("stderr").asText("");
            if (body.path("exitCode").asInt(1) != 0) {
                // 「letsblogは登録されたコマンドではない」だけが未導入。それ以外(wpPathの誤り・PHPの
                // 致命的エラー等)は未導入と取り違えず、stderrをログに残して例外にする。
                if (LetsblogPluginStatus.isCommandMissing(stderr) || LetsblogPluginStatus.isCommandMissing(stdout)) {
                    return LetsblogPluginStatus.notInstalled();
                }
                log.warn("wp letsblog statusが失敗しました (wpSlug={}): {}", creds.wpSlug(), stderr);
                throw new AgentOperationException("wp letsblog statusの実行に失敗しました: "
                        + (stderr.isBlank() ? stdout : stderr).strip());
            }
            try {
                return LetsblogPluginStatus.fromStatusOutput(stdout);
            } catch (IllegalArgumentException e) {
                log.warn("wp letsblog statusの出力を解釈できません (wpSlug={}): {}", creds.wpSlug(), stderr);
                throw new AgentOperationException(e.getMessage(), e);
            }
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("letsblogプラグインの状態取得に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * letsblog プラグインを(再)導入して有効化し、導入後の状態を返す(issue #1557)。
     */
    public LetsblogPluginStatus installLetsblogPlugin(WordPressCredentials creds) {
        try {
            post("/wp-cli/letsblog-install", Map.of("slug", creds.wpSlug()));
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("letsblogプラグインの導入に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
        return letsblogPluginStatus(creds);
    }

    /**
     * エージェント経由で `wp letsblog sync` を実行し、タグ定義・統合CSS等をプラグインへ渡す(issue #1558)。
     * 送信は wp-cli だけで行う(REST APIは使わない)。導入処理は走らせない(未導入のサイトへは送らない)。
     *
     * @return プラグインが保存した内容のハッシュ。期待ハッシュと違えば例外
     */
    public String syncLetsblogPlugin(WordPressCredentials creds, String payload, String expectedHash) {
        try {
            JsonNode body = post("/wp-cli/letsblog-sync",
                    Map.of("slug", creds.wpSlug(), "payload", payload, "hash", expectedHash));
            String stdout = body.path("stdout").asText("");
            String stderr = body.path("stderr").asText("");
            if (body.path("exitCode").asInt(1) != 0) {
                log.warn("wp letsblog syncが失敗しました (wpSlug={}): {}", creds.wpSlug(), stderr);
                throw new AgentOperationException("wp letsblog syncの実行に失敗しました: "
                        + (stderr.isBlank() ? stdout : stderr).strip());
            }
            String savedHash;
            try {
                savedHash = LetsblogPluginStatus.syncHashFromSyncOutput(stdout);
            } catch (IllegalArgumentException e) {
                throw new AgentOperationException(e.getMessage(), e);
            }
            if (!savedHash.equals(expectedHash)) {
                throw new AgentOperationException("プラグインが保存したハッシュ(" + savedHash
                        + ")が送った内容のハッシュ(" + expectedHash + ")と一致しません");
            }
            return savedHash;
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("letsblogプラグインへの同期に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * エージェント経由で `wp letsblog sns ...` を実行し、標準出力を返す(issue #1574)。秘密を含む標準入力
     * (`config set` のJSON)はエージェントがwp-cliの標準入力へ渡し、引数・一時ファイル・ログには残さない。
     * 失敗したときの例外にも標準入力は含めない(wp-cliのstderr/stdoutだけ)。
     */
    public String letsblogSns(WordPressCredentials creds, LetsblogSnsCommand command, String sns, String stdin) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("slug", creds.wpSlug());
            request.put("command", command.wire());
            putIfPresent(request, "sns", sns);
            putIfPresent(request, "stdin", stdin);
            JsonNode body = post("/wp-cli/letsblog-sns", request);
            String stdout = body.path("stdout").asText("");
            String stderr = body.path("stderr").asText("");
            if (body.path("exitCode").asInt(1) != 0) {
                log.warn("wp letsblog sns {} が失敗しました (wpSlug={}): {}", command.wire(), creds.wpSlug(), stderr);
                throw new AgentOperationException("wp letsblog sns " + command.wire() + " の実行に失敗しました: "
                        + (stderr.isBlank() ? stdout : stderr).strip());
            }
            return stdout.strip();
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("SNS告知の操作に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * 内容を`wp letsblog preview`(エージェント経由のwp-cliだけ。REST APIは使わない)でプラグインへ渡し、
     * 投稿を作らない署名付きプレビューURLを返す(issue #1561)。
     *
     * @param ttlSeconds 有効期限(秒)。nullならプラグインの規定値
     */
    public SignedPreview createSignedPreview(WordPressCredentials creds, String payload, Integer ttlSeconds) {
        try {
            Map<String, Object> request = new java.util.HashMap<>();
            request.put("slug", creds.wpSlug());
            request.put("payload", payload);
            if (ttlSeconds != null) {
                request.put("ttl", ttlSeconds);
            }
            JsonNode body = post("/wp-cli/letsblog-preview", request);
            String stdout = body.path("stdout").asText("");
            String stderr = body.path("stderr").asText("");
            if (body.path("exitCode").asInt(1) != 0) {
                log.warn("wp letsblog previewが失敗しました (wpSlug={}): {}", creds.wpSlug(), stderr);
                throw new AgentOperationException("wp letsblog previewの実行に失敗しました: "
                        + (stderr.isBlank() ? stdout : stderr).strip());
            }
            try {
                return SignedPreview.fromPreviewOutput(stdout);
            } catch (IllegalArgumentException e) {
                throw new AgentOperationException(e.getMessage(), e);
            }
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("署名付きプレビューURLの発行に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * 記事プレビュー(非公開投稿の実表示)向けに、サイト管理者としてログイン済みと同等のCookieを発行する。
     */
    public AuthCookie generateAuthCookie(WordPressCredentials creds) {
        try {
            JsonNode body = post("/wp-cli/generate-auth-cookie", Map.of("slug", creds.wpSlug(), "userLogin", creds.username()));
            return new AuthCookie(body.path("name").asText(), body.path("value").asText());
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("認証Cookieの発行に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    public List<CmsPostSummary> listPosts(WordPressCredentials creds, String postType) {
        try {
            JsonNode body = post("/wp-cli/post-list", Map.of("slug", creds.wpSlug(), "postType", postType));
            List<CmsPostSummary> results = new ArrayList<>();
            body.path("posts").forEach(item -> results.add(new CmsPostSummary(
                    item.path("id").asText(), item.path("title").asText(),
                    item.path("slug").asText(), item.path("status").asText(), postType)));
            return results;
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("投稿/ページ一覧の取得に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * 指定スラッグ(WordPressの保存形へ正規化済み)の投稿(post_type=post)のIDを返す(issue #1431)。
     * provision-agentの`post-list`が`postName`指定時にゴミ箱以外の全ステータスを対象に絞り込む。
     * 失敗時は例外を投げる(呼び出し側が新規作成へ進んで重複を作らないため)。
     */
    public List<String> findPostIdsBySlug(WordPressCredentials creds, String slug) {
        try {
            JsonNode body = post("/wp-cli/post-list",
                    Map.of("slug", creds.wpSlug(), "postType", "post", "postName", slug));
            List<String> ids = new ArrayList<>();
            body.path("posts").forEach(item -> {
                if (slug.equalsIgnoreCase(item.path("slug").asText())) {
                    ids.add(item.path("id").asText());
                }
            });
            return ids;
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("スラッグによる既存投稿の照会に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    public void updatePostStatus(WordPressCredentials creds, String postId, String status) {
        try {
            post("/wp-cli/post-status-update", Map.of("slug", creds.wpSlug(), "postId", postId, "status", status));
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("投稿/ページのステータス変更に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    private static final List<String> SETTINGS_MEDIA_KEYS =
            List.of("site_icon", "custom_logo", "header_image", "background_image");

    /** ガベージコレクション画面(issue #500)向けにメディアライブラリの一覧を取得する。 */
    public List<CmsMediaSummary> listMedia(WordPressCredentials creds) {
        try {
            JsonNode body = post("/wp-cli/media-list", Map.of("slug", creds.wpSlug()));
            List<CmsMediaSummary> results = new ArrayList<>();
            body.path("media").forEach(item -> results.add(new CmsMediaSummary(
                    item.path("id").asText(), item.path("guid").asText(), item.path("title").asText(),
                    item.path("mimeType").asText(), item.path("uploadedAt").asText())));
            return results;
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("メディア一覧の取得に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * ガベージコレクション画面(issue #500)向けに、公開投稿タイプ全件の本文/アイキャッチと
     * 主要なサイト設定が参照する添付ファイルIDをまとめて取得する。SSH版
     * (WordPressSshOperations#scanMediaReferences)と同じJSON形状をエージェント側(wp-cli evalの
     * PHPコードもSSH版と同一、provision-agentの/wp-cli/media-reference-scan参照)から受け取る。
     */
    public CmsMediaReferenceScan scanMediaReferences(WordPressCredentials creds) {
        try {
            JsonNode body = post("/wp-cli/media-reference-scan", Map.of("slug", creds.wpSlug()));
            List<CmsPostContentSummary> posts = new ArrayList<>();
            body.path("posts").forEach(item -> posts.add(new CmsPostContentSummary(
                    item.path("id").asText(), item.path("postType").asText(), item.path("status").asText(),
                    item.path("content").asText(), item.path("thumbnailId").asText())));
            JsonNode settingsNode = body.path("settings");
            Map<String, String> settings = new java.util.LinkedHashMap<>();
            for (String key : SETTINGS_MEDIA_KEYS) {
                settings.put(key, settingsNode.path(key).asText(""));
            }
            return new CmsMediaReferenceScan(posts, settings);
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("メディア参照スキャンに失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * メディア(添付ファイル)を完全に削除する(issue #500)。{@link #deletePost}と異なり
     * ゴミ箱を経由せず物理削除する(provision-agent側で`wp post delete --force`を実行する)。
     */
    /**
     * メディアも{@code wp_posts}(post_type=attachment)ベースの削除のため、{@link #deletePost}と
     * 同じく対象が存在しない場合(issue #1070)は{@link PostNotFoundException}(404)を投げる。
     */
    public void deleteMedia(WordPressCredentials creds, String mediaId) {
        try {
            post("/wp-cli/media-delete", Map.of("slug", creds.wpSlug(), "mediaId", mediaId));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new PostNotFoundException("メディア '" + mediaId + "' が見つかりません: " + agentErrorDetail(e));
            }
            throw new AgentOperationException("メディアの削除に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    public MediaUploadResult uploadMedia(WordPressCredentials creds, String filename, String contentType, byte[] data) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("slug", creds.wpSlug());
        // メディアのpost metaへ記録するsha256は、エージェントがアップロードされたファイルから計算する
        // (issue #1436)。client送信値は信用しないため、ここでは送らない。
        form.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        try {
            JsonNode body = client.post()
                    .uri("/wp-cli/media-upload")
                    .header("X-Provision-Token", provisionToken)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            return new MediaUploadResult(body.path("mediaId").asText(), body.path("guid").asText());
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("WordPressメディアのアップロードに失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * 内容ハッシュ(sha256)を記録したメディアを、1回のエージェント呼び出しでまとめて照会する
     * (issue #1432)。ゴミ箱のメディアはエージェント側で除外される。失敗時は例外を投げる。
     */
    public Map<String, MediaUploadResult> findMediaBySha256(WordPressCredentials creds,
                                                           java.util.Collection<String> sha256s) {
        List<String> hashes = sha256s.stream().distinct().toList();
        if (hashes.isEmpty()) {
            return Map.of();
        }
        try {
            // ハッシュの形式検証はエージェント側(index.php)が行う。
            JsonNode body = post("/wp-cli/media-find-by-hash", Map.of("slug", creds.wpSlug(), "hashes", hashes));
            Map<String, MediaUploadResult> found = new HashMap<>();
            body.path("media").forEach(item -> found.putIfAbsent(item.path("sha256").asText(),
                    new MediaUploadResult(item.path("id").asText(), item.path("guid").asText())));
            return found;
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("内容ハッシュによるメディアの照会に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException(accessFailureMessage(e), e);
        }
    }

    /**
     * managedサイトのwp-cliはDockerイメージへビルド時インストール済みのため、
     * インストール操作自体が不要(外部SSHサイト向けのinstallWpCliに相当する操作はない)。
     */
    public WpCliInstallResult installWpCli(WordPressCredentials creds) {
        throw new UnsupportedOperationException("自動構築されたWordPressサイトにはwp-cliが最初からインストール済みです");
    }

    private JsonNode post(String path, Object body) {
        return client.post()
                .uri(path)
                .header("X-Provision-Token", provisionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private boolean isConnectivityStage(HttpStatusCode status) {
        return status.value() == 403 || status.value() == 404;
    }

    private String agentErrorDetail(RestClientResponseException e) {
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            if (body != null) {
                String detail = body.path("detail").asText(null);
                String error = body.path("error").asText(null);
                if (detail != null && !detail.isBlank()) {
                    return error != null ? error + ": " + detail : detail;
                }
                if (error != null && !error.isBlank()) {
                    return error;
                }
            }
        } catch (RuntimeException ignored) {
            // JSON以外/パース不能な応答本文はそのままフォールバックで扱う
        }
        return e.getStatusCode() + " " + e.getResponseBodyAsString();
    }

    private void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }
}
