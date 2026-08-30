package com.letsblog.api.service;

import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Site;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * サイトの基本情報・CMS接続情報の参照。Site本体・認証情報の所有権はproject-serviceへ移った
 * (issue #577 stage2、サイトのCRUD・登録・接続確認・SSH鍵解決等はproject-service側の
 * {@code com.letsblog.project.service.SiteService}が正)。
 *
 * <p>legacy-apiに残るドメイン(一括管理/カテゴリ・タグ・プラグイン・テーマ・投稿の環境間比較・
 * 投稿publish/delete・記事プレビュー・project_userとのWordPressユーザー同期等)は、いずれも
 * WordPress用CmsAdapter(SSH/wp-cliエージェント)への深い依存のため#577では移設せずlegacy-apiに残る。
 * これらは本クラスを介して{@link ProjectServiceClient}経由でproject-serviceからサイトの基本情報・
 * 解決済み(sshKeyPairId参照を実鍵PEMへ解決済み)のCMS認証情報を取得する(issue #577 stage3。
 * 旧{@code SiteRepository}(ローカルJPA)への直接アクセスは廃止した)。
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

    /** 複数idをまとめて取得する(見つからないidはスキップする)。 */
    public List<Site> getAllById(List<Long> siteIds) {
        return siteIds.stream().map(this::getById).filter(Optional::isPresent).map(Optional::get).toList();
    }

    /** siteKey指定でサイトを取得する。未登録なら{@link SiteNotFoundException}。 */
    public Site getBySiteKey(String siteKey) {
        return findBySiteKeyOptional(siteKey)
                .orElseThrow(() -> new SiteNotFoundException("siteKey '" + siteKey + "' は登録されていません"));
    }

    /** siteKey指定でサイトを取得する。未登録ならempty。 */
    public Optional<Site> findBySiteKeyOptional(String siteKey) {
        return projectServiceClient.getSiteByKey(siteKey).map(this::toSite);
    }

    /** 全サイトの基本情報一覧(ContentBridgeController#sitesが使う、サイト名表示用)。 */
    public List<Site> listAll() {
        return projectServiceClient.listSites().stream().map(this::toSite).toList();
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

    /**
     * サイトがSSH transportで設定されているか(環境同期でSSH管理サイトを同期元として扱えるかの
     * 判定に使用。issue #511)。認証情報の取得に失敗した場合はfalseを返す。
     */
    public boolean isSshConfigured(Site site) {
        try {
            CmsCredentials credentials = getCredentials(site.getSiteKey());
            return credentials instanceof CmsCredentials.WordPressCredentials wp && wp.isSsh();
        } catch (RuntimeException e) {
            return false;
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
        site.setCreatedAt(bridge.createdAt());
        site.setUpdatedAt(bridge.updatedAt());
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
