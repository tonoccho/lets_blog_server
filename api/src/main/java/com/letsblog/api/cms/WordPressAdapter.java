package com.letsblog.api.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * WordPress REST API(wp-json/wp/v2)を利用したCmsAdapter実装。
 * 認証はサイトごとの Basic認証(ユーザー名 + アプリケーションパスワード)を使う。
 */
@Component
public class WordPressAdapter implements CmsAdapter {

    private static final String DEFAULT_CATEGORY_NAME = "Uncategorized";
    private static final String DEFAULT_TAG_NAME = "Let's Blog";

    private final RestClient.Builder restClientBuilder;

    public WordPressAdapter(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public CmsType supportedType() {
        return CmsType.WORDPRESS;
    }

    @Override
    public PostResult createOrUpdatePost(CmsCredentials credentials, PostContent content, String existingPostId) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        RestClient client = buildClient(creds);

        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("title", content.title());
        body.put("content", content.htmlContent());
        body.put("status", content.status());
        if (content.slug() != null && !content.slug().isBlank()) {
            body.put("slug", content.slug());
        }
        if (content.categoryIds() != null && !content.categoryIds().isEmpty()) {
            ArrayNode categories = body.putArray("categories");
            content.categoryIds().forEach(id -> categories.add(Integer.parseInt(id)));
        }
        if (content.tagIds() != null && !content.tagIds().isEmpty()) {
            ArrayNode tags = body.putArray("tags");
            content.tagIds().forEach(id -> tags.add(Integer.parseInt(id)));
        }

        String path = existingPostId == null ? "/wp-json/wp/v2/posts" : "/wp-json/wp/v2/posts/" + existingPostId;

        try {
            JsonNode response = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            return new PostResult(
                    response.get("id").asText(),
                    response.get("link").asText(),
                    response.get("status").asText()
            );
        } catch (RestClientResponseException e) {
            throw new CmsApiException("WordPress投稿の作成/更新に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public MediaUploadResult uploadMedia(CmsCredentials credentials, String filename, String contentType, byte[] data) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        RestClient client = buildClient(creds);

        try {
            JsonNode response = client.post()
                    .uri("/wp-json/wp/v2/media")
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(data)
                    .retrieve()
                    .body(JsonNode.class);

            return new MediaUploadResult(response.get("id").asText(), response.get("source_url").asText());
        } catch (RestClientResponseException e) {
            throw new CmsApiException("WordPressメディアのアップロードに失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public List<String> resolveCategories(CmsCredentials credentials, List<String> names) {
        return resolveTerms((CmsCredentials.WordPressCredentials) credentials, "/wp-json/wp/v2/categories", names);
    }

    @Override
    public List<String> resolveTags(CmsCredentials credentials, List<String> names) {
        return resolveTerms((CmsCredentials.WordPressCredentials) credentials, "/wp-json/wp/v2/tags", names);
    }

    private List<String> resolveTerms(CmsCredentials.WordPressCredentials credentials, String path, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        RestClient client = buildClient(credentials);
        List<String> ids = new ArrayList<>();

        for (String name : names) {
            ids.add(findOrCreateTerm(client, path, name));
        }
        return ids;
    }

    private String findOrCreateTerm(RestClient client, String path, String name) {
        try {
            JsonNode searchResult = client.get()
                    .uri(uriBuilder -> uriBuilder.path(path).queryParam("search", name).build())
                    .retrieve()
                    .body(JsonNode.class);

            if (searchResult != null && searchResult.isArray()) {
                for (JsonNode term : searchResult) {
                    if (term.get("name").asText().equalsIgnoreCase(name)) {
                        return term.get("id").asText();
                    }
                }
            }

            ObjectNode createBody = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            createBody.put("name", name);

            JsonNode created = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createBody)
                    .retrieve()
                    .body(JsonNode.class);

            return created.get("id").asText();
        } catch (RestClientResponseException e) {
            throw new CmsApiException("カテゴリ/タグ '" + name + "' の解決に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public String provisionDefaultCategory(CmsCredentials credentials) {
        return resolveCategories(credentials, List.of(DEFAULT_CATEGORY_NAME)).get(0);
    }

    @Override
    public String provisionDefaultTag(CmsCredentials credentials) {
        return resolveTags(credentials, List.of(DEFAULT_TAG_NAME)).get(0);
    }

    @Override
    public String provisionAuthor(CmsCredentials credentials, AuthorProvisioningRequest request) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        RestClient client = buildClient(creds);
        String email = request.email();

        String existingId = findExistingAuthorId(client, email);
        if (existingId != null) {
            return updateAuthor(client, existingId, request);
        }

        ObjectNode body = profileBody(request);
        body.put("username", email.substring(0, email.indexOf('@')));
        body.put("email", email);
        // このパスワードはLet's Blog側では保持・利用しない(WordPress側にauthorレコードを
        // 作成するために必須の項目のため、ランダム値を生成して使い捨てる)
        body.put("password", generateRandomPassword());

        try {
            JsonNode created = client.post()
                    .uri("/wp-json/wp/v2/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return created.get("id").asText();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                String fallbackId = findExistingAuthorId(client, email);
                if (fallbackId != null) {
                    return updateAuthor(client, fallbackId, request);
                }
            }
            throw new CmsApiException(authorErrorMessage("作成", e), e);
        }
    }

    private String updateAuthor(RestClient client, String userId, AuthorProvisioningRequest request) {
        ObjectNode body = profileBody(request);
        try {
            JsonNode updated = client.put()
                    .uri("/wp-json/wp/v2/users/" + userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return updated.get("id").asText();
        } catch (RestClientResponseException e) {
            throw new CmsApiException(authorErrorMessage("更新", e), e);
        }
    }

    /**
     * 403 Forbiddenの場合、登録済み認証情報の管理者権限不足が原因である可能性が高いため、
     * 生のWordPressエラーの前に分かりやすい案内文を付加する。
     */
    private String authorErrorMessage(String action, RestClientResponseException e) {
        String detail = e.getStatusCode() + " " + e.getResponseBodyAsString();
        if (e.getStatusCode().value() == 403) {
            return "WordPress著者の" + action + "に失敗しました: サイトに登録されている認証情報のWordPress"
                    + "アカウントにユーザー作成・更新権限(Administrator)がない可能性があります。"
                    + "サイト管理画面の編集機能で、管理者権限を持つアカウントのアプリケーションパスワードに"
                    + "更新してください。(詳細: " + detail + ")";
        }
        return "WordPress著者の" + action + "に失敗しました: " + detail;
    }

    private ObjectNode profileBody(AuthorProvisioningRequest request) {
        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (request.firstName() != null) {
            body.put("first_name", request.firstName());
        }
        if (request.lastName() != null) {
            body.put("last_name", request.lastName());
        }
        if (request.displayName() != null) {
            body.put("name", request.displayName());
        }
        if (request.websiteUrl() != null) {
            body.put("url", request.websiteUrl());
        }
        if (request.bio() != null) {
            body.put("description", request.bio());
        }
        // localeはWordPress側にインストールされている言語パックのenumでしか許容されず、
        // 未インストールの言語(既定インストールのja_JPなど)を送ると400エラーになるため送信しない。
        ArrayNode roles = body.putArray("roles");
        roles.add(request.wpRole() != null ? request.wpRole() : "author");
        return body;
    }

    private String findExistingAuthorId(RestClient client, String email) {
        try {
            JsonNode searchResult = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/wp-json/wp/v2/users").queryParam("search", email).build())
                    .retrieve()
                    .body(JsonNode.class);
            if (searchResult != null && searchResult.isArray() && !searchResult.isEmpty()) {
                return searchResult.get(0).get("id").asText();
            }
        } catch (RestClientResponseException | ResourceAccessException e) {
            // 検索に失敗した場合は新規作成を試みる(呼び出し元でハンドリング)
        }
        return null;
    }

    private String generateRandomPassword() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Override
    public boolean testConnection(CmsCredentials credentials) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        RestClient client = buildClient(creds);
        try {
            client.get().uri("/wp-json/wp/v2/users/me").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException | ResourceAccessException e) {
            return false;
        }
    }

    @Override
    public boolean hasAuthorProvisioningCapability(CmsCredentials credentials) {
        CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
        RestClient client = buildClient(creds);
        try {
            JsonNode me = client.get()
                    .uri("/wp-json/wp/v2/users/me?context=edit")
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode capabilities = me != null ? me.get("capabilities") : null;
            return capabilities != null && capabilities.path("create_users").asBoolean(false);
        } catch (RestClientResponseException | ResourceAccessException e) {
            return false;
        }
    }

    private RestClient buildClient(CmsCredentials.WordPressCredentials credentials) {
        String token = Base64.getEncoder().encodeToString(
                (credentials.username() + ":" + credentials.appPassword()).getBytes(StandardCharsets.UTF_8));

        return restClientBuilder.clone()
                .baseUrl(credentials.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token)
                .build();
    }
}
