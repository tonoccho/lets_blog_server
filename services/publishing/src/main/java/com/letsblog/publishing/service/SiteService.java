package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.domain.Site;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * サイトの基本情報・CMS接続情報の参照。Site本体・認証情報の所有権はproject-serviceにある
 * (issue #577 stage2)。legacy-apiの{@code com.letsblog.api.service.SiteService}を、一括管理・
 * 環境間比較機能({@link BulkManagementService}等)一式と共にpublishing-serviceへ移設したもの
 * (issue #708、Epic #551 C6-2)。project-serviceの内部ブリッジ({@link ProjectServiceClient}）経由で
 * サイトの基本情報・解決済み(sshKeyPairId参照を実鍵PEMへ解決済み)のCMS認証情報を取得する。
 */
@Service
public class SiteService {

    private final ProjectServiceClient projectServiceClient;

    public SiteService(ProjectServiceClient projectServiceClient) {
        this.projectServiceClient = projectServiceClient;
    }

    /** id指定でサイトを取得する。未登録ならempty。 */
    public Optional<Site> getById(Long siteId) {
        return projectServiceClient.getSite(siteId).map(this::toSite);
    }

    public CmsCredentials getCredentials(String siteKey) {
        ProjectServiceClient.SiteCredentialsBridge bridge = projectServiceClient.getCredentials(siteKey);
        return buildCredentialsFromMap(bridge.cmsType(), bridge.credentials());
    }

    /**
     * 一括管理(カテゴリ/タグ/プラグイン/テーマ比較)で、非managedサイトをどの経路で扱えるかを判定する。
     * WordPress以外のCMS種別・認証情報の取得失敗の場合はfalseになる
     * (呼び出し元でエラーにせず「対象外」表示にフォールバックするため)。
     */
    public SiteDataSource resolveDataSource(Site site) {
        if (site.isManagedWordpress()) {
            return new SiteDataSource(true, null);
        }
        if (site.getCmsType() != CmsType.WORDPRESS) {
            return new SiteDataSource(false, null);
        }
        try {
            CmsCredentials credentials = getCredentials(site.getSiteKey());
            if (credentials instanceof CmsCredentials.WordPressCredentials wp) {
                return new SiteDataSource(false, wp.isSsh() ? wp : null);
            }
        } catch (RuntimeException e) {
            return new SiteDataSource(false, null);
        }
        return new SiteDataSource(false, null);
    }

    /**
     * 非managedサイトはSSH接続情報(transport=SSH)が設定されていれば一括管理の対象にできる。
     * テーマのインストール/有効化/削除を含め、書き込み系の操作はすべてSSH経由(wp-cli)で行う。
     */
    public record SiteDataSource(
            boolean managed,
            CmsCredentials.WordPressCredentials sshCredentials
    ) {
        public boolean hasSsh() {
            return sshCredentials != null;
        }

        public boolean isUnavailable() {
            return !managed && sshCredentials == null;
        }
    }

    private Site toSite(ProjectServiceClient.SiteBridge bridge) {
        Site site = new Site();
        site.setId(bridge.id());
        site.setSiteKey(bridge.siteKey());
        site.setName(bridge.name());
        site.setBaseUrl(bridge.baseUrl());
        site.setCmsType(bridge.cmsType());
        site.setManagedWordpress(bridge.managedWordpress());
        site.setWpSlug(bridge.wpSlug());
        return site;
    }

    private CmsCredentials buildCredentialsFromMap(CmsType cmsType, Map<String, String> credentials) {
        return switch (cmsType) {
            case WORDPRESS -> new CmsCredentials.WordPressCredentials(
                    credentials.get("baseUrl"),
                    credentials.get("username"),
                    credentials.get("transport"),
                    credentials.get("sshHost"),
                    parseSshPort(credentials.get("sshPort")),
                    credentials.get("sshUser"),
                    credentials.get("wpPath"),
                    credentials.get("sshPrivateKeyPem"),
                    credentials.get("sshHostKeyFingerprint"),
                    credentials.get("wpSlug"));
        };
    }

    private Integer parseSshPort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sshPortは数値で指定してください: " + value);
        }
    }
}
