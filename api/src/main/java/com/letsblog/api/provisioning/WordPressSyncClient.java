package com.letsblog.api.provisioning;

import com.letsblog.api.service.ProvisioningException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * 常駐wordpressコンテナ内の内部限定プロビジョニングエージェント(ポート9000)へ、
 * プロジェクト環境間(ローカル/テスト/本番)のテーマ・プラグイン・DB同期を依頼するクライアント。
 * WordPressProvisioningClientと同じエージェント・同じ共有シークレットを利用する。
 */
@Component
public class WordPressSyncClient {

    private final RestClient client;
    private final String provisionToken;

    public WordPressSyncClient(
            @Value("${app.wordpress-provision-base-url}") String baseUrl,
            @Value("${app.wordpress-provision-token}") String provisionToken) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.provisionToken = provisionToken;
    }

    public void sync(SyncCommand command) {
        try {
            client.post()
                    .uri("/sync")
                    .header("X-Provision-Token", provisionToken)
                    .body(command)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ProvisioningException("環境同期に失敗しました: " + e.getMessage(), e);
        }
    }

    public record SyncCommand(
            String fromSlug,
            String fromDbName,
            String toSlug,
            String toDbName,
            List<String> targets
    ) {
    }
}
