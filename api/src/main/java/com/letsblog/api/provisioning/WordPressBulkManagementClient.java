package com.letsblog.api.provisioning;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * 常駐wordpressコンテナ内の内部限定プロビジョニングエージェント(ポート9000)へ、
 * カテゴリ作成/編集/削除・プラグイン/テーマのインストール(SLUG指定・zipアップロード)/
 * 有効化/無効化/削除を依頼するクライアント。環境同期(WordPressSyncClient)と異なり
 * 「1環境の失敗が他環境への実行を止めない」という一括管理の方針に合わせ、
 * apply系メソッドは例外を投げず常に結果(BulkApplyResult)を返す。
 */
@Component
public class WordPressBulkManagementClient {

    private final RestClient client;
    private final String provisionToken;

    public WordPressBulkManagementClient(
            @Value("${app.wordpress-provision-base-url}") String baseUrl,
            @Value("${app.wordpress-provision-token}") String provisionToken) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.provisionToken = provisionToken;
    }

    public BulkApplyResult apply(BulkApplyCommand command) {
        try {
            Map<String, String> body = client.post()
                    .uri("/bulk-management")
                    .header("X-Provision-Token", provisionToken)
                    .body(command)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, String>>() {
                    });
            return resultOf(body);
        } catch (RestClientException e) {
            return BulkApplyResult.failed(e.getMessage());
        }
    }

    public BulkApplyResult applyZip(String slug, String action, byte[] zipBytes, String filename) {
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("slug", slug);
            form.add("action", action);
            form.add("file", new ByteArrayResource(zipBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            Map<String, String> body = client.post()
                    .uri("/bulk-management/upload")
                    .header("X-Provision-Token", provisionToken)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, String>>() {
                    });
            return resultOf(body);
        } catch (RestClientException e) {
            return BulkApplyResult.failed(e.getMessage());
        }
    }

    /**
     * 1環境分のカテゴリ一覧を取得する(比較テーブル・親カテゴリ解決に使用)。
     * 取得に失敗した場合は空リストを返す(呼び出し元でエラーとして扱わず、単に該当なしとする)。
     */
    public List<CategoryInfo> listCategories(String slug) {
        return listTerms("/categories", "categories", slug);
    }

    /**
     * 1環境分のタグ一覧を取得する(比較テーブルに使用)。タグは階層を持たないため
     * CategoryInfo.parentSlug()は常にnullになる。
     */
    public List<CategoryInfo> listTags(String slug) {
        return listTerms("/tags", "tags", slug);
    }

    private List<CategoryInfo> listTerms(String uri, String bodyKey, String slug) {
        try {
            Map<String, Object> body = client.post()
                    .uri(uri)
                    .header("X-Provision-Token", provisionToken)
                    .body(Map.of("slug", slug))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (body == null || !(body.get(bodyKey) instanceof List<?> rawList)) {
                return List.of();
            }
            return rawList.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> term = (Map<String, Object>) item;
                        return new CategoryInfo(
                                asString(term.get("name")),
                                asString(term.get("slug")),
                                asString(term.get("parentSlug")),
                                asString(term.get("description")));
                    })
                    .toList();
        } catch (RestClientException e) {
            return List.of();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private BulkApplyResult resultOf(Map<String, String> body) {
        String status = body != null ? body.get("status") : null;
        return "skipped".equals(status) ? BulkApplyResult.skipped() : BulkApplyResult.success();
    }

    /**
     * value/categorySlug/categoryParentSlug/categoryDescription/categoryTargetSlugの意味は
     * actionによって変わる(ApplyToEnvironmentRequestのフィールドコメントを参照)。
     */
    public record BulkApplyCommand(
            String slug, String action, String value,
            String categorySlug, String categoryParentSlug, String categoryDescription,
            String categoryTargetSlug) {
    }

    public record CategoryInfo(String name, String slug, String parentSlug, String description) {
    }

    public record BulkApplyResult(String status, String errorMessage) {
        public static BulkApplyResult success() {
            return new BulkApplyResult("SUCCESS", null);
        }

        public static BulkApplyResult skipped() {
            return new BulkApplyResult("SKIPPED", null);
        }

        public static BulkApplyResult failed(String message) {
            return new BulkApplyResult("FAILED", message);
        }
    }
}
