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
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.cms.PostContent;
import com.letsblog.publishing.cms.PostResult;
import com.letsblog.publishing.cms.ReferencePost;
import com.letsblog.publishing.cms.WpCliInstallResult;
import com.letsblog.publishing.config.LegacyJacksonRestClientConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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

    public WordPressAgentOperations(
            RestClient.Builder restClientBuilder,
            @Value("${app.wordpress-provision-base-url}") String baseUrl,
            @Value("${app.wordpress-provision-token}") String provisionToken) {
        RestClient.Builder clonedBuilder = restClientBuilder.clone().baseUrl(baseUrl);
        LegacyJacksonRestClientConfig.preferJackson2(clonedBuilder);
        this.client = clonedBuilder.build();
        this.provisionToken = provisionToken;
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
            log.warn("エージェントへの接続に失敗しました (wpSlug={}): {}", creds.wpSlug(), e.getMessage());
            return ConnectionCheckResult.failure("エージェントへの接続に失敗しました: " + e.getMessage());
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
        }
    }

    public void updatePostStatus(WordPressCredentials creds, String postId, String status) {
        try {
            post("/wp-cli/post-status-update", Map.of("slug", creds.wpSlug(), "postId", postId, "status", status));
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("投稿/ページのステータス変更に失敗しました: " + agentErrorDetail(e), e);
        } catch (ResourceAccessException e) {
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
        }
    }

    public MediaUploadResult uploadMedia(WordPressCredentials creds, String filename, String contentType, byte[] data) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("slug", creds.wpSlug());
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
            throw new AgentOperationException("エージェントへの接続に失敗しました: " + e.getMessage(), e);
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
