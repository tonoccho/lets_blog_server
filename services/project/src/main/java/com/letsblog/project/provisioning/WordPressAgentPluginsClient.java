package com.letsblog.project.provisioning;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 読み取りタイムアウト(秒)。プラグイン一覧の取得のみで短時間に終わるため60秒。接続は3秒。 */
    static final long DEFAULT_READ_TIMEOUT_SECONDS = 60;

    @Autowired
    public WordPressAgentPluginsClient(
            @Value("${app.wordpress-provision-base-url}") String baseUrl,
            @Value("${app.wordpress-provision-token}") String provisionToken) {
        this(baseUrl, provisionToken, AgentRestClients.DEFAULT_CONNECT_TIMEOUT,
                Duration.ofSeconds(DEFAULT_READ_TIMEOUT_SECONDS));
    }

    /** タイムアウトを指定できるコンストラクタ(テスト用)。 */
    WordPressAgentPluginsClient(String baseUrl, String provisionToken, Duration connectTimeout, Duration readTimeout) {
        this.client = AgentRestClients.create(baseUrl, connectTimeout, readTimeout);
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
