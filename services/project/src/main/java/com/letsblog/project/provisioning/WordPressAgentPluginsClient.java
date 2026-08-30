package com.letsblog.project.provisioning;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 常駐wordpressコンテナ内の内部限定プロビジョニングエージェント(ポート9000)へ、自動構築(managed)サイトの
 * インストール済みプラグイン一覧を問い合わせるクライアント(issue #577 stage2)。legacy-apiの
 * WordPressBulkManagementClient#listPluginsと同じエンドポイントを呼ぶが、StaticContentGenerationService
 * (有効化済みプラグイン名の取得のみ)に必要な最小限のみをこちらに移設する(bulk-management/比較機能自体は
 * legacy-apiに残る。PR説明を参照)。
 */
@Component
public class WordPressAgentPluginsClient {

    private final RestClient client;
    private final String provisionToken;

    public WordPressAgentPluginsClient(
            @Value("${app.wordpress-provision-base-url}") String baseUrl,
            @Value("${app.wordpress-provision-token}") String provisionToken) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.provisionToken = provisionToken;
    }

    public List<String> listActivePluginNames(String wpSlug) {
        try {
            Map<String, Object> body = client.post()
                    .uri("/plugins")
                    .header("X-Provision-Token", provisionToken)
                    .body(Map.of("slug", wpSlug))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (body == null || !(body.get("plugins") instanceof List<?> rawList)) {
                return List.of();
            }
            return rawList.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<?, ?>) item)
                    .filter(item -> "active".equalsIgnoreCase(String.valueOf(item.get("status"))))
                    .map(item -> String.valueOf(item.get("name")))
                    .toList();
        } catch (RestClientException e) {
            return List.of();
        }
    }
}
