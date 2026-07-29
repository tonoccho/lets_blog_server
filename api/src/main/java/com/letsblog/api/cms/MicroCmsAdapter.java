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

import java.util.ArrayList;
import java.util.List;

/**
 * microCMS(日本製ヘッドレスCMS)向けのCmsAdapter実装。
 * 投稿本体はContent API(X-MICROCMS-API-KEY)、画像アップロードは
 * Management API(専用のmanagementApiKey)を使い分ける。
 *
 * カテゴリ/タグは固定タクソノミーを持たないため、ユーザーが事前に用意した
 * name フィールドを持つlist型API(categoriesEndpoint/tagsEndpoint)に対して
 * WordPressと同様の「検索して無ければ作成」を行う。
 */
@Component
public class MicroCmsAdapter implements CmsAdapter {

    private static final String API_KEY_HEADER = "X-MICROCMS-API-KEY";
    private static final String DEFAULT_CATEGORY_NAME = "Uncategorized";
    private static final String DEFAULT_TAG_NAME = "Let's Blog";

    private final RestClient.Builder restClientBuilder;

    public MicroCmsAdapter(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public CmsType supportedType() {
        return CmsType.MICROCMS;
    }

    @Override
    public PostResult createOrUpdatePost(CmsCredentials credentials, PostContent content, String existingPostId) {
        CmsCredentials.MicroCmsCredentials creds = (CmsCredentials.MicroCmsCredentials) credentials;

        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("title", content.title());
        body.put("body", content.htmlContent());
        if (content.slug() != null && !content.slug().isBlank()) {
            body.put("slug", content.slug());
        }
        if (content.categoryIds() != null && !content.categoryIds().isEmpty()) {
            ArrayNode categories = body.putArray("category");
            content.categoryIds().forEach(categories::add);
        }
        if (content.tagIds() != null && !content.tagIds().isEmpty()) {
            ArrayNode tags = body.putArray("tags");
            content.tagIds().forEach(tags::add);
        }

        RestClient client = buildContentApiClient(creds);
        String contentUrl = contentApiUrl(creds, creds.postsEndpoint());

        try {
            JsonNode response;
            if (existingPostId == null) {
                response = client.post()
                        .uri(contentUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } else {
                response = client.patch()
                        .uri(contentUrl + "/" + existingPostId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            }

            String id = response.get("id").asText();
            return new PostResult(id, contentUrl + "/" + id, content.status());
        } catch (RestClientResponseException e) {
            throw new CmsApiException("microCMS投稿の作成/更新に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public MediaUploadResult uploadMedia(CmsCredentials credentials, String filename, String contentType, byte[] data) {
        CmsCredentials.MicroCmsCredentials creds = (CmsCredentials.MicroCmsCredentials) credentials;
        RestClient client = buildManagementApiClient(creds);

        try {
            JsonNode response = client.post()
                    .uri("https://" + creds.serviceId() + ".microcms-management.io/api/v1/media")
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(data)
                    .retrieve()
                    .body(JsonNode.class);

            return new MediaUploadResult(response.get("id").asText(), response.get("url").asText());
        } catch (RestClientResponseException e) {
            throw new CmsApiException("microCMSメディアのアップロードに失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public List<String> resolveCategories(CmsCredentials credentials, List<String> names) {
        CmsCredentials.MicroCmsCredentials creds = (CmsCredentials.MicroCmsCredentials) credentials;
        return resolveTerms(creds, creds.categoriesEndpoint(), names);
    }

    @Override
    public List<String> resolveTags(CmsCredentials credentials, List<String> names) {
        CmsCredentials.MicroCmsCredentials creds = (CmsCredentials.MicroCmsCredentials) credentials;
        return resolveTerms(creds, creds.tagsEndpoint(), names);
    }

    private List<String> resolveTerms(CmsCredentials.MicroCmsCredentials creds, String endpoint, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        RestClient client = buildContentApiClient(creds);
        String url = contentApiUrl(creds, endpoint);
        List<String> ids = new ArrayList<>();

        for (String name : names) {
            ids.add(findOrCreateTerm(client, url, name));
        }
        return ids;
    }

    private String findOrCreateTerm(RestClient client, String endpoint, String name) {
        try {
            JsonNode searchResult = client.get()
                    .uri(uriBuilder -> uriBuilder.path(endpoint)
                            .queryParam("filters", "name[equals]" + name)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode contents = searchResult != null ? searchResult.get("contents") : null;
            if (contents != null && contents.isArray()) {
                for (JsonNode term : contents) {
                    if (term.get("name").asText().equalsIgnoreCase(name)) {
                        return term.get("id").asText();
                    }
                }
            }

            ObjectNode createBody = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            createBody.put("name", name);

            JsonNode created = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createBody)
                    .retrieve()
                    .body(JsonNode.class);

            return created.get("id").asText();
        } catch (RestClientResponseException e) {
            throw new CmsApiException("microCMSカテゴリ/タグ '" + name + "' の解決に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
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

    /**
     * microCMSはlist型APIの仕様がサービスごとに異なり著者フィールドの標準化された仕様がないため、
     * 現時点では著者プロビジョニングは未対応(将来対応、spec/phase5/05-true-provisioning.md 未決事項)。
     */
    @Override
    public String provisionAuthor(CmsCredentials credentials, AuthorProvisioningRequest request) {
        return null;
    }

    @Override
    public boolean testConnection(CmsCredentials credentials) {
        CmsCredentials.MicroCmsCredentials creds = (CmsCredentials.MicroCmsCredentials) credentials;
        RestClient client = buildContentApiClient(creds);
        String url = contentApiUrl(creds, creds.postsEndpoint()) + "?limit=1";
        try {
            client.get().uri(url).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException | ResourceAccessException e) {
            return false;
        }
    }

    private String contentApiUrl(CmsCredentials.MicroCmsCredentials creds, String endpoint) {
        return "https://" + creds.serviceId() + ".microcms.io/api/v1/" + endpoint;
    }

    private RestClient buildContentApiClient(CmsCredentials.MicroCmsCredentials creds) {
        return restClientBuilder.clone()
                .defaultHeader(API_KEY_HEADER, creds.apiKey())
                .build();
    }

    private RestClient buildManagementApiClient(CmsCredentials.MicroCmsCredentials creds) {
        return restClientBuilder.clone()
                .defaultHeader(API_KEY_HEADER, creds.managementApiKey())
                .build();
    }
}
