package com.letsblog.api.provisioning;

import com.letsblog.api.service.ProvisioningException;
import com.letsblog.api.service.SiteAlreadyProvisionedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 常駐wordpressコンテナ内の内部限定プロビジョニングエージェント(ポート9000)を呼び出すクライアント。
 * nginxには公開されておらず、lbs-net内部からのみ到達可能なエンドポイントを共有シークレットで保護している。
 */
@Component
public class WordPressProvisioningClient {

    private final RestClient client;
    private final String provisionToken;

    public WordPressProvisioningClient(
            @Value("${app.wordpress-provision-base-url}") String baseUrl,
            @Value("${app.wordpress-provision-token}") String provisionToken) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.provisionToken = provisionToken;
    }

    public ProvisionResult provision(ProvisionCommand command) {
        try {
            return client.post()
                    .uri("/provision")
                    .header("X-Provision-Token", provisionToken)
                    .body(command)
                    .retrieve()
                    .body(ProvisionResult.class);
        } catch (HttpClientErrorException.Conflict e) {
            // 409は「今回のリクエストでは何も作成していない」ことを意味するため、
            // 呼び出し元が誤って既存サイトをdeprovisionしないよう専用の例外にする(issue #315)。
            throw new SiteAlreadyProvisionedException("WordPressサイトは既に存在します: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new ProvisioningException("WordPress自動構築に失敗しました: " + e.getMessage(), e);
        }
    }

    public void deprovision(String slug, String dbName) {
        try {
            client.post()
                    .uri("/deprovision")
                    .header("X-Provision-Token", provisionToken)
                    .body(Map.of("slug", slug, "dbName", dbName))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ProvisioningException("WordPressインスタンスの削除に失敗しました: " + e.getMessage(), e);
        }
    }

    public record ProvisionCommand(
            String slug,
            String dbName,
            String title,
            String adminUser,
            String adminEmail,
            String adminPassword,
            String locale
    ) {
    }

    public record ProvisionResult(
            String url,
            String adminUser,
            String applicationPassword
    ) {
    }
}
