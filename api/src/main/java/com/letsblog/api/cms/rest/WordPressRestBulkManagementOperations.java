package com.letsblog.api.cms.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.api.cms.CmsApiException;
import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ssh.WordPressSshOperations.SshApplyResult;
import com.letsblog.api.config.LegacyJacksonRestClientConfig;
import com.letsblog.api.domain.BulkOperationType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WordPress REST API(wp-json/wp/v2, Application Password認証)経由でカテゴリ・タグ・プラグインの
 * 一覧取得・作成・編集・削除を行う。{@link com.letsblog.api.cms.ssh.WordPressSshOperations}のREST版。
 * 非managedサイトではSSHを優先して使い、SSHが未設定、またはSSH経由の実行が失敗した場合にこちらを使う
 * (優先順位・フォールバックの判定はBulkManagementService#applyToSiteが行う)。
 * <p>
 * 注意: WordPressコアのREST APIには**テーマの作成・有効化・削除エンドポイントが存在しない**
 * (一覧取得のみ、WP5.7+)。そのためテーマの書き込み系操作はこのクラスでは提供しない
 * (呼び出し元はSSHにフォールバックするか、SSHも無ければエラーにする)。
 */
@Component
public class WordPressRestBulkManagementOperations {

    private static final int PAGE_SIZE = 100;

    private final RestClient.Builder restClientBuilder;

    public WordPressRestBulkManagementOperations(RestClient.Builder restClientBuilder) {
        LegacyJacksonRestClientConfig.preferJackson2(restClientBuilder);
        this.restClientBuilder = restClientBuilder;
    }

    public List<CategoryInfo> listCategories(WordPressCredentials creds) {
        return listTerms(creds, "categories");
    }

    public List<CategoryInfo> listTags(WordPressCredentials creds) {
        return listTerms(creds, "tags");
    }

    private List<CategoryInfo> listTerms(WordPressCredentials creds, String endpoint) {
        List<JsonNode> terms = fetchAllPages(creds, "/wp-json/wp/v2/" + endpoint,
                endpoint.equals("categories") ? "カテゴリ" : "タグ");
        Map<String, String> slugById = new HashMap<>();
        for (JsonNode term : terms) {
            slugById.put(term.path("id").asText(), term.path("slug").asText());
        }
        return terms.stream()
                .map(term -> {
                    String parentId = term.path("parent").asText("0");
                    String parentSlug = !"0".equals(parentId) ? slugById.get(parentId) : null;
                    return new CategoryInfo(term.path("id").asText(), term.path("name").asText(),
                            term.path("slug").asText(), parentSlug, term.path("description").asText());
                })
                .toList();
    }

    /**
     * per_page=100でページングしながら全件取得する(X-WP-TotalPagesヘッダを見て次ページの要否を判定)。
     */
    private List<JsonNode> fetchAllPages(WordPressCredentials creds, String path, String label) {
        RestClient client = buildClient(creds);
        List<JsonNode> result = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        do {
            int currentPage = page;
            ResponseEntity<JsonNode> response;
            try {
                response = client.get()
                        .uri(uriBuilder -> uriBuilder.path(path)
                                .queryParam("per_page", PAGE_SIZE)
                                .queryParam("page", currentPage)
                                .build())
                        .retrieve()
                        .toEntity(JsonNode.class);
            } catch (RestClientResponseException e) {
                throw new CmsApiException(label + "一覧の取得に失敗しました: " + e.getStatusCode() + " "
                        + e.getResponseBodyAsString(), e);
            } catch (ResourceAccessException e) {
                throw new CmsApiException(label + "一覧の取得に失敗しました: " + e.getMessage(), e);
            }
            JsonNode body = response.getBody();
            if (body != null && body.isArray()) {
                body.forEach(result::add);
            }
            String totalPagesHeader = response.getHeaders().getFirst("X-WP-TotalPages");
            totalPages = totalPagesHeader != null ? Integer.parseInt(totalPagesHeader) : 1;
            page++;
        } while (page <= totalPages);
        return result;
    }

    public List<PluginThemeInfo> listPlugins(WordPressCredentials creds) {
        return listPluginsOrThemes(creds, "plugins");
    }

    public List<PluginThemeInfo> listThemes(WordPressCredentials creds) {
        return listPluginsOrThemes(creds, "themes");
    }

    private List<PluginThemeInfo> listPluginsOrThemes(WordPressCredentials creds, String endpoint) {
        RestClient client = buildClient(creds);
        JsonNode body;
        try {
            body = client.get().uri("/wp-json/wp/v2/" + endpoint).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new CmsApiException((endpoint.equals("plugins") ? "プラグイン" : "テーマ") + "一覧の取得に失敗しました: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new CmsApiException((endpoint.equals("plugins") ? "プラグイン" : "テーマ") + "一覧の取得に失敗しました: "
                    + e.getMessage(), e);
        }
        if (body == null || !body.isArray()) {
            return List.of();
        }
        List<PluginThemeInfo> result = new ArrayList<>();
        for (JsonNode item : body) {
            String identifier = endpoint.equals("plugins")
                    ? item.path("plugin").asText()
                    : item.path("stylesheet").asText();
            String slug = endpoint.equals("plugins") ? pluginSlugOf(identifier) : identifier;
            String status = item.path("status").asText();
            result.add(new PluginThemeInfo(slug, "active".equalsIgnoreCase(status) ? "active" : status));
        }
        return result;
    }

    /**
     * pluginフィールド("akismet/akismet.php")からslug("akismet")部分だけを取り出す。
     */
    private String pluginSlugOf(String pluginFile) {
        int slash = pluginFile.indexOf('/');
        return slash >= 0 ? pluginFile.substring(0, slash) : pluginFile;
    }

    /**
     * カテゴリ/タグの作成・編集・削除。既存slug衝突ならskipped、対象/親が見つからなければfailedを返す
     * (例外を投げずSshApplyResultと同型の結果を返し、呼び出し元がSSH版と同じように扱えるようにする)。
     */
    public SshApplyResult applyTerm(
            WordPressCredentials creds, BulkOperationType type, String value, String slug, String parentSlug,
            String description, String targetSlug) {
        try {
            String endpoint = (type == BulkOperationType.CATEGORY_CREATE || type == BulkOperationType.CATEGORY_EDIT
                    || type == BulkOperationType.CATEGORY_DELETE) ? "categories" : "tags";
            return switch (type) {
                case CATEGORY_CREATE, TAG_CREATE -> createTerm(creds, endpoint, value, slug, parentSlug, description);
                case CATEGORY_EDIT, TAG_EDIT ->
                        updateTerm(creds, endpoint, value, slug, parentSlug, description, targetSlug);
                case CATEGORY_DELETE, TAG_DELETE -> deleteTerm(creds, endpoint, targetSlug);
                default -> throw new IllegalArgumentException("REST経由ではサポートされていない操作です: " + type);
            };
        } catch (CmsApiException e) {
            return SshApplyResult.failed(e);
        }
    }

    private SshApplyResult createTerm(
            WordPressCredentials creds, String endpoint, String value, String slug, String parentSlug,
            String description) {
        List<CategoryInfo> terms = listTerms(creds, endpoint);
        if (findBySlug(terms, slug) != null) {
            return SshApplyResult.skipped();
        }
        ObjectNode body = termBody(value, slug, description);
        if (endpoint.equals("categories") && parentSlug != null && !parentSlug.isBlank()) {
            CategoryInfo parent = findBySlug(terms, parentSlug);
            if (parent == null) {
                throw new CmsApiException("親カテゴリ(slug: " + parentSlug + ")が見つかりません");
            }
            body.put("parent", Integer.parseInt(parent.termId()));
        }
        postOrPut(creds, "/wp-json/wp/v2/" + endpoint, body, "作成");
        return SshApplyResult.success();
    }

    private SshApplyResult updateTerm(
            WordPressCredentials creds, String endpoint, String value, String slug, String parentSlug,
            String description, String targetSlug) {
        List<CategoryInfo> terms = listTerms(creds, endpoint);
        CategoryInfo target = findBySlug(terms, targetSlug);
        if (target == null) {
            throw new CmsApiException("対象(slug: " + targetSlug + ")が見つかりません");
        }
        ObjectNode body = termBody(value, slug, description);
        if (endpoint.equals("categories") && parentSlug != null && !parentSlug.isBlank()) {
            CategoryInfo parent = findBySlug(terms, parentSlug);
            if (parent == null) {
                throw new CmsApiException("親カテゴリ(slug: " + parentSlug + ")が見つかりません");
            }
            if (parent.termId().equals(target.termId())) {
                throw new CmsApiException("親カテゴリに自分自身は指定できません");
            }
            body.put("parent", Integer.parseInt(parent.termId()));
        }
        postOrPut(creds, "/wp-json/wp/v2/" + endpoint + "/" + target.termId(), body, "更新");
        return SshApplyResult.success();
    }

    private SshApplyResult deleteTerm(WordPressCredentials creds, String endpoint, String targetSlug) {
        List<CategoryInfo> terms = listTerms(creds, endpoint);
        CategoryInfo target = findBySlug(terms, targetSlug);
        if (target == null) {
            return SshApplyResult.skipped();
        }
        RestClient client = buildClient(creds);
        try {
            client.delete()
                    .uri(uriBuilder -> uriBuilder.path("/wp-json/wp/v2/" + endpoint + "/" + target.termId())
                            .queryParam("force", true)
                            .build())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new CmsApiException("削除に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new CmsApiException("削除に失敗しました: " + e.getMessage(), e);
        }
        return SshApplyResult.success();
    }

    private ObjectNode termBody(String value, String slug, String description) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("name", value);
        body.put("slug", slug);
        if (description != null && !description.isBlank()) {
            body.put("description", description);
        }
        return body;
    }

    private void postOrPut(WordPressCredentials creds, String path, ObjectNode body, String actionLabel) {
        RestClient client = buildClient(creds);
        try {
            client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new CmsApiException(actionLabel + "に失敗しました: " + e.getStatusCode() + " "
                    + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new CmsApiException(actionLabel + "に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * プラグインのインストール・有効化・無効化・削除。WP5.5+のコアREST API(/wp/v2/plugins)を使う。
     * テーマの書き込みはコアに対応エンドポイントが無いため、このクラスでは提供しない
     * (呼び出し元でTHEME系のtypeを渡さないこと)。
     */
    public SshApplyResult applyPlugin(WordPressCredentials creds, BulkOperationType type, String slug) {
        try {
            return switch (type) {
                case PLUGIN_INSTALL -> installPluginIfMissing(creds, slug);
                case PLUGIN_ACTIVATE -> setPluginStatus(creds, slug, "active");
                case PLUGIN_DEACTIVATE -> setPluginStatus(creds, slug, "inactive");
                case PLUGIN_DELETE -> deletePlugin(creds, slug);
                default -> throw new IllegalArgumentException("REST経由ではサポートされていない操作です: " + type);
            };
        } catch (CmsApiException e) {
            return SshApplyResult.failed(e);
        }
    }

    private SshApplyResult installPluginIfMissing(WordPressCredentials creds, String slug) {
        List<PluginThemeInfo> installed = listPluginsOrThemes(creds, "plugins");
        if (installed.stream().anyMatch(info -> info.name().equals(slug))) {
            return SshApplyResult.skipped();
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("slug", slug);
        body.put("status", "inactive");
        postOrPut(creds, "/wp-json/wp/v2/plugins", body, "プラグインのインストール");
        return SshApplyResult.success();
    }

    private SshApplyResult setPluginStatus(WordPressCredentials creds, String slug, String status) {
        String pluginFile = resolvePluginFile(creds, slug);
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("status", status);
        RestClient client = buildClient(creds);
        try {
            client.put()
                    .uri("/wp-json/wp/v2/plugins/" + pluginFile)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new CmsApiException("active".equals(status)
                    ? "プラグインの有効化に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString()
                    : "プラグインの無効化に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new CmsApiException("プラグインの状態変更に失敗しました: " + e.getMessage(), e);
        }
        return SshApplyResult.success();
    }

    private SshApplyResult deletePlugin(WordPressCredentials creds, String slug) {
        String pluginFile = resolvePluginFile(creds, slug);
        // 有効化されている場合は先に無効化する(WordPress REST APIは有効なプラグインの削除を拒否する)
        try {
            setPluginStatus(creds, slug, "inactive");
        } catch (CmsApiException ignored) {
            // 既に無効化されている場合のエラーは無視してよい
        }
        RestClient client = buildClient(creds);
        try {
            client.delete().uri("/wp-json/wp/v2/plugins/" + pluginFile).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new CmsApiException("プラグインの削除に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new CmsApiException("プラグインの削除に失敗しました: " + e.getMessage(), e);
        }
        return SshApplyResult.success();
    }

    private String resolvePluginFile(WordPressCredentials creds, String slug) {
        RestClient client = buildClient(creds);
        JsonNode body;
        try {
            body = client.get().uri("/wp-json/wp/v2/plugins").retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new CmsApiException("プラグイン一覧の取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new CmsApiException("プラグイン一覧の取得に失敗しました: " + e.getMessage(), e);
        }
        if (body != null && body.isArray()) {
            for (JsonNode item : body) {
                String pluginFile = item.path("plugin").asText();
                if (pluginSlugOf(pluginFile).equals(slug)) {
                    return pluginFile;
                }
            }
        }
        throw new CmsApiException("対象(slug: " + slug + ")が見つかりません");
    }

    private CategoryInfo findBySlug(List<CategoryInfo> terms, String slug) {
        return terms.stream().filter(t -> t.slug().equalsIgnoreCase(slug)).findFirst().orElse(null);
    }

    /**
     * デフォルトのJDK HttpClientが送るUser-Agent(例: "Java-http-client/21")やAcceptヘッダ未指定は、
     * 一部レンタルサーバーのMod_Security(WAF)に「406 Not Acceptable」でブロックされることがあるため、
     * ブラウザ相当のUser-AgentとAcceptヘッダを明示的に付与する。
     */
    private RestClient buildClient(WordPressCredentials creds) {
        String token = Base64.getEncoder().encodeToString(
                (creds.username() + ":" + creds.appPassword()).getBytes(StandardCharsets.UTF_8));
        return restClientBuilder.clone()
                .baseUrl(creds.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (compatible; LetsBlogBulkManagement/1.0; +https://letsblog.local)")
                .build();
    }

    public record CategoryInfo(String termId, String name, String slug, String parentSlug, String description) {
    }

    public record PluginThemeInfo(String name, String status) {
    }
}
