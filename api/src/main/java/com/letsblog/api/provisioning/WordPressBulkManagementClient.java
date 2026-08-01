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

import java.util.Map;

/**
 * 常駐wordpressコンテナ内の内部限定プロビジョニングエージェント(ポート9000)へ、
 * カテゴリ作成・プラグイン/テーマインストール(SLUG指定・zipアップロード双方)を依頼するクライアント。
 * 環境同期(WordPressSyncClient)と異なり「1環境の失敗が他環境への実行を止めない」という
 * 一括管理の方針に合わせ、例外を投げず常に結果(BulkApplyResult)を返す。
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

    public BulkApplyResult apply(String slug, String action, String value) {
        return apply(slug, action, value, null, null, null);
    }

    /**
     * categorySlug/categoryParentName/categoryDescriptionはaction=categoryの場合のみ有効(他は無視される)。
     */
    public BulkApplyResult apply(
            String slug, String action, String value,
            String categorySlug, String categoryParentName, String categoryDescription) {
        try {
            Map<String, String> body = client.post()
                    .uri("/bulk-management")
                    .header("X-Provision-Token", provisionToken)
                    .body(new BulkApplyCommand(slug, action, value, categorySlug, categoryParentName, categoryDescription))
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

    private BulkApplyResult resultOf(Map<String, String> body) {
        String status = body != null ? body.get("status") : null;
        return "skipped".equals(status) ? BulkApplyResult.skipped() : BulkApplyResult.success();
    }

    public record BulkApplyCommand(
            String slug, String action, String value,
            String categorySlug, String categoryParentName, String categoryDescription) {
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
