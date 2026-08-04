package com.letsblog.api.cms.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.api.cms.AuthorProvisioningRequest;
import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.cms.PostContent;
import com.letsblog.api.cms.PostResult;
import com.letsblog.api.cms.WpCliInstallResult;
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
 * 常駐wordpressコンテナ内の内部限定プロビジョニングエージェント(wordpress/provision-agent)
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
        this.client = restClientBuilder.clone().baseUrl(baseUrl).build();
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
        payload.put("htmlContent", content.htmlContent() != null ? content.htmlContent() : "");
        if (existingPostId != null) {
            payload.put("existingPostId", existingPostId);
        }
        if (content.slug() != null && !content.slug().isBlank()) {
            payload.put("postSlug", content.slug());
        }
        if (content.categoryIds() != null && !content.categoryIds().isEmpty()) {
            payload.put("categoryIds", content.categoryIds());
        }
        if (content.tagIds() != null && !content.tagIds().isEmpty()) {
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

    public void deletePost(WordPressCredentials creds, String postId) {
        try {
            post("/wp-cli/post-delete", Map.of("slug", creds.wpSlug(), "postId", postId));
        } catch (RestClientResponseException e) {
            throw new AgentOperationException("WordPress投稿の削除に失敗しました: " + agentErrorDetail(e), e);
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
