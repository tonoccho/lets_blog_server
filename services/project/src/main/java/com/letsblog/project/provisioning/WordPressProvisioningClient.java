package com.letsblog.project.provisioning;

import com.letsblog.project.service.ProvisioningException;
import com.letsblog.project.service.SiteAlreadyProvisionedException;
import com.letsblog.project.service.SiteNotFoundException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 常駐wordpressコンテナ内の内部限定プロビジョニングエージェント(ポート9000)を呼び出すクライアント
 * (issue #577 stage2、legacy-apiから移設)。nginxには公開されておらず、lbs-net内部からのみ到達可能な
 * エンドポイントを共有シークレットで保護している。project-serviceはlbs-net上でこのエージェントへ
 * 直接到達できるため、legacy-api経由のブリッジは不要(legacy-apiと同じ環境変数
 * WORDPRESS_PROVISION_BASE_URL/WORDPRESS_PROVISION_TOKENをdocker-compose.ymlで共有する)。
 */
@Component
public class WordPressProvisioningClient {

    private final RestClient client;
    private final String provisionToken;

    /**
     * 読み取りタイムアウト(秒)。サイト自動構築の実測最大は240秒のため、それを下回らないよう300秒
     * (実測最大の1.25倍の余裕)とする。短くすると正常な構築を切ってしまう。provision/adopt/deprovisionで
     * 同じ値を共有する。接続タイムアウトは3秒。
     */
    static final long DEFAULT_READ_TIMEOUT_SECONDS = 300;

    @Autowired
    public WordPressProvisioningClient(
            @Value("${app.wordpress-provision-base-url}") String baseUrl,
            @Value("${app.wordpress-provision-token}") String provisionToken) {
        this(baseUrl, provisionToken, AgentRestClients.DEFAULT_CONNECT_TIMEOUT,
                Duration.ofSeconds(DEFAULT_READ_TIMEOUT_SECONDS));
    }

    /** タイムアウトを指定できるコンストラクタ(テスト用)。 */
    WordPressProvisioningClient(String baseUrl, String provisionToken, Duration connectTimeout, Duration readTimeout) {
        this.client = AgentRestClients.create(baseUrl, connectTimeout, readTimeout);
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
            throw new SiteAlreadyProvisionedException(
                    "WordPressサイト '" + command.slug() + "' は既に構築済みですがDBには登録されていません。"
                            + "既存のWordPress環境を保持したまま登録するには、サイトの取り込み機能を使用してください: " + e.getMessage(),
                    e);
        } catch (RestClientException e) {
            throw new ProvisioningException("WordPress自動構築に失敗しました: " + e.getMessage(), e);
        }
    }

    /** DBには未登録だが実体が既に存在するWordPressサイトを取り込む(issue #317)。 */
    public ProvisionResult adopt(AdoptCommand command) {
        try {
            return client.post()
                    .uri("/adopt")
                    .header("X-Provision-Token", provisionToken)
                    .body(command)
                    .retrieve()
                    .body(ProvisionResult.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new SiteNotFoundException(
                    "WordPressサイト '" + command.slug() + "' の取り込みに失敗しました: " + e.getMessage());
        } catch (RestClientException e) {
            throw new ProvisioningException("WordPressサイトの取り込みに失敗しました: " + e.getMessage(), e);
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

    public record AdoptCommand(
            String slug,
            String adminUser
    ) {
    }
}
