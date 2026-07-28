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

    private RestClient buildClient(CmsCredentials.WordPressCredentials credentials) {
        String token = Base64.getEncoder().encodeToString(
                (credentials.username() + ":" + credentials.appPassword()).getBytes(StandardCharsets.UTF_8));

        return restClientBuilder.clone()
                .baseUrl(credentials.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token)
                .build();
    }
}
