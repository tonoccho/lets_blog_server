package com.letsblog.project.provisioning;

import com.letsblog.project.service.ProvisioningException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * 常駐wordpressコンテナ内の内部限定プロビジョニングエージェント(ポート9000)へ、
 * プロジェクト環境間(ローカル/テスト/本番)のテーマ・プラグイン・DB同期を依頼するクライアント
 * (issue #577 stage2、legacy-apiから移設)。WordPressProvisioningClientと同じエージェント・
 * 同じ共有シークレットを利用する。
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

    public void importDatabase(String toSlug, String toDbName, String fromUrl, String fromPrefix, byte[] sqlDump) {
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("slug", toSlug);
            form.add("dbName", toDbName);
            form.add("fromUrl", fromUrl);
            form.add("fromPrefix", fromPrefix);
            form.add("file", new ByteArrayResource(sqlDump) {
                @Override
                public String getFilename() {
                    return "dump.sql";
                }
            });
            client.post()
                    .uri("/db-import")
                    .header("X-Provision-Token", provisionToken)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ProvisioningException("環境同期(DBインポート)に失敗しました: " + e.getMessage(), e);
        }
    }

    public void importMedia(String toSlug, byte[] mediaTarGz) {
        importContentDirectory(toSlug, "/media-import", "media.tar.gz", mediaTarGz, "メディア");
    }

    public void importThemes(String toSlug, byte[] themesTarGz) {
        importContentDirectory(toSlug, "/theme-import", "themes.tar.gz", themesTarGz, "テーマ");
    }

    private void importContentDirectory(
            String toSlug, String uri, String filename, byte[] tarGz, String label) {
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("slug", toSlug);
            form.add("file", new ByteArrayResource(tarGz) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            client.post()
                    .uri(uri)
                    .header("X-Provision-Token", provisionToken)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ProvisioningException("環境同期(" + label + "インポート)に失敗しました: " + e.getMessage(), e);
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
