package com.letsblog.api.provisioning;

import com.letsblog.api.service.ProvisioningException;
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

    /**
     * SSH管理サイトから取得したDBダンプ(wp db export)を、managedサイトのDBへインポートする
     * (issue #511)。managedサイト同士の同期({@link #sync})と異なりダンプがJava側に一度存在するため
     * multipart/form-dataでファイルとして送信する(WordPressBulkManagementClient#applyZipと同じ方式)。
     * fromPrefixは同期元のテーブルプレフィックス(WordPressインストーラがランダム生成することがあり、
     * 同期先と異なりうる)。provision-agent側で同期先のプレフィックスへ変換してからインポートするために使う。
     */
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

    /**
     * SSH管理サイトから取得したメディア(wp-content/uploads)のtar.gzを、managedサイトへインポートする
     * (issue #511)。{@link #importDatabase}と同じくmultipart/form-dataでファイルとして送信する。
     */
    public void importMedia(String toSlug, byte[] mediaTarGz) {
        importContentDirectory(toSlug, "/media-import", "media.tar.gz", mediaTarGz, "メディア");
    }

    /**
     * SSH管理サイトから取得したテーマ(wp-content/themes)のtar.gzを、managedサイトへインポートする
     * (issue #511)。{@link #importMedia}と同じ方式。プラグインは対象外(ProjectEnvironmentSyncService参照)。
     */
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
