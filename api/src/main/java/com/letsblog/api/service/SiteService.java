package com.letsblog.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.WpCliInstallResult;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Site;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.SiteConnectionCheckResult;
import com.letsblog.api.dto.SiteDetailResponse;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.dto.SiteUpdateRequest;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class SiteService {

    private final SiteRepository siteRepository;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final ProvisioningService provisioningService;
    private final UserRepository userRepository;

    public SiteService(SiteRepository siteRepository, CredentialCipher credentialCipher, ObjectMapper objectMapper,
                        CmsAdapterFactory cmsAdapterFactory, ProvisioningService provisioningService,
                        UserRepository userRepository) {
        this.siteRepository = siteRepository;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.provisioningService = provisioningService;
        this.userRepository = userRepository;
    }

    /**
     * サイトを登録する。保存前にCMS側へのプロビジョニング(デフォルトカテゴリ・タグ・著者の作成)を実行し、
     * 致命的な失敗の場合は登録自体を行わない(ProvisioningServiceは個々のリソースの部分的失敗は許容するため、
     * 実際にここで例外が伝播するのはCMSアダプタ解決に失敗する等の致命的なケースのみ)。
     * 登録後に疎通確認(軽量なAPI呼び出し)も行うが、こちらは失敗しても登録自体は取り消さず、
     * 結果をレスポンスの connectionCheckStatus で通知する。
     *
     * @param actorId サイト登録を行った操作者のユーザーID。著者プロビジョニングに使う(null可)。
     */
    @AuditLog(action = AuditLogAction.SITE_REGISTERED, resourceType = "SITE")
    @Transactional
    public SiteResponse register(SiteRegisterRequest request, Long actorId) {
        if (siteRepository.existsBySiteKey(request.siteKey())) {
            throw new IllegalArgumentException("siteKey '" + request.siteKey() + "' は既に登録されています");
        }

        validateCredentials(request.cmsType(), request.credentials());

        CmsCredentials credentials = buildCredentialsFromMap(request.cmsType(), request.credentials());
        String actorEmail = resolveActorEmail(actorId);

        ProvisioningService.ProvisioningResult provisioningResult =
                provisioningService.provisionSite(request.cmsType(), credentials, actorEmail);
        if (provisioningResult.categoryError != null || provisioningResult.tagError != null
                || provisioningResult.authorError != null) {
            log.warn("Provisioning partial failure for site '{}': category={}, tag={}, author={}",
                    request.siteKey(), provisioningResult.categoryError, provisioningResult.tagError,
                    provisioningResult.authorError);
        }

        ConnectionCheckResult connectionResult = runConnectionCheck(request.cmsType(), request.credentials());
        Map<String, String> credentialsToStore = shouldPinHostKeyFingerprint(request.credentials(), connectionResult)
                ? withObservedHostKeyFingerprint(request.credentials(), connectionResult)
                : request.credentials();

        Site site = new Site();
        site.setName(request.name());
        site.setSiteKey(request.siteKey());
        site.setCmsType(request.cmsType());
        site.setBaseUrl(resolveDisplayBaseUrl(request.cmsType(), request.credentials()));
        site.setCredentialsEncrypted(credentialCipher.encrypt(writeCredentialsJson(credentialsToStore)));

        Site saved = siteRepository.save(site);

        return SiteResponse.from(saved, connectionResult.ok());
    }

    /**
     * 既存サイトに対してプロビジョニングを再実行する(管理画面用)。
     */
    @Transactional
    public ProvisioningService.ProvisioningResult reprovision(Long siteId, Long actorId) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));

        CmsCredentials credentials = getCredentials(site.getSiteKey());
        String actorEmail = resolveActorEmail(actorId);
        return provisioningService.provisionSite(site.getCmsType(), credentials, actorEmail);
    }

    private String resolveActorEmail(Long actorId) {
        if (actorId == null) {
            return null;
        }
        return userRepository.findById(actorId).map(User::getEmail).orElse(null);
    }

    private ConnectionCheckResult runConnectionCheck(CmsType cmsType, Map<String, String> credentialsMap) {
        try {
            CmsCredentials credentials = buildCredentialsFromMap(cmsType, credentialsMap);
            CmsAdapter adapter = cmsAdapterFactory.resolve(cmsType);
            return adapter.testConnection(credentials);
        } catch (Exception e) {
            log.warn("疎通確認に失敗しました (cmsType={}): {}", cmsType, e.getMessage(), e);
            return ConnectionCheckResult.failure(e.getMessage());
        }
    }

    /**
     * SSHトランスポートのホスト鍵は初回接続時にTOFUで受理される。まだ`sshHostKeyFingerprint`が
     * 保存されておらず、かつ疎通確認が成功してfingerprintが観測された場合のみピン留め対象とする
     * (以後の接続はこのfingerprintとの完全一致のみ許可され、なりすましを検知できるようになる)。
     */
    private boolean shouldPinHostKeyFingerprint(Map<String, String> credentials, ConnectionCheckResult result) {
        return result.ok() && result.observedHostKeyFingerprint() != null
                && !StringUtils.hasText(credentials.get("sshHostKeyFingerprint"));
    }

    private Map<String, String> withObservedHostKeyFingerprint(Map<String, String> credentials, ConnectionCheckResult result) {
        Map<String, String> updated = new HashMap<>(credentials);
        updated.put("sshHostKeyFingerprint", result.observedHostKeyFingerprint());
        return updated;
    }

    @Transactional(readOnly = true)
    public List<SiteResponse> list() {
        return siteRepository.findAll().stream().map(SiteResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Site getBySiteKey(String siteKey) {
        return siteRepository.findBySiteKey(siteKey)
                .orElseThrow(() -> new SiteNotFoundException("siteKey '" + siteKey + "' は登録されていません"));
    }

    @Transactional(readOnly = true)
    public CmsCredentials getCredentials(String siteKey) {
        Site site = getBySiteKey(siteKey);
        return buildCredentialsFromMap(site.getCmsType(), getRawCredentials(site));
    }

    /**
     * サイトの認証情報を復号し、生のMapとして返す(汎用列を優先し、Phase2以前のレガシー列にフォールバック)。
     * 編集(update)時に既存値へのpatchを行うために使う。
     */
    private Map<String, String> getRawCredentials(Site site) {
        Map<String, String> credentials;
        if (site.getCredentialsEncrypted() != null) {
            credentials = readCredentialsJson(credentialCipher.decrypt(site.getCredentialsEncrypted()));
        } else if (site.getWpUsername() != null && site.getWpAppPasswordEncrypted() != null) {
            credentials = new HashMap<>();
            credentials.put("baseUrl", site.getBaseUrl());
            credentials.put("username", site.getWpUsername());
            credentials.put("appPassword", credentialCipher.decrypt(site.getWpAppPasswordEncrypted()));
        } else {
            throw new IllegalStateException("サイト '" + site.getSiteKey() + "' の認証情報が無効です");
        }

        // managedWordpressサイトは常にエージェント経由のwp-cli運用とする。この上書きにより、
        // transport=AGENT導入(2026-08-01)より前に構築済みのサイトも含め、保存済みcredentialsの
        // 内容に関わらず一貫してエージェント経由になる(個別のデータ移行が不要)。
        if (site.isManagedWordpress()) {
            credentials = new HashMap<>(credentials);
            credentials.put("transport", "AGENT");
            credentials.put("wpSlug", site.getWpSlug());
        }
        return credentials;
    }

    /**
     * サイトの表示名・認証情報を編集する。credentialsは指定されたキーのみ既存値へ上書きする部分patch方式。
     * managedWordpressサイトの認証情報はインフラ側で自動管理されているため編集不可。
     * レガシー列(wpUsername/wpAppPasswordEncrypted)のみを持つサイトを編集した場合、
     * この機会に汎用のcredentialsEncrypted列へ統合する。
     */
    @Transactional
    public SiteResponse update(Long id, SiteUpdateRequest request) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));

        if (StringUtils.hasText(request.name())) {
            site.setName(request.name());
        }

        Boolean connectionOk = null;
        if (request.credentials() != null && !request.credentials().isEmpty()) {
            if (site.isManagedWordpress()) {
                throw new IllegalArgumentException("自動構築されたWordPressサイトの認証情報は編集できません");
            }
            Map<String, String> merged = new HashMap<>(getRawCredentials(site));
            merged.putAll(request.credentials());
            validateCredentials(site.getCmsType(), merged);

            ConnectionCheckResult connectionResult = runConnectionCheck(site.getCmsType(), merged);
            Map<String, String> credentialsToStore = shouldPinHostKeyFingerprint(merged, connectionResult)
                    ? withObservedHostKeyFingerprint(merged, connectionResult)
                    : merged;
            site.setCredentialsEncrypted(credentialCipher.encrypt(writeCredentialsJson(credentialsToStore)));
            connectionOk = connectionResult.ok();
        }

        Site saved = siteRepository.save(site);
        return connectionOk != null ? SiteResponse.from(saved, connectionOk) : SiteResponse.from(saved);
    }

    /**
     * 既存サイトの疎通確認を再実行する(接続結果自体は永続化しない、リクエストの都度計算)。
     * ただしSSHトランスポートで初回接続のホスト鍵fingerprintが新たに観測された場合のみ、
     * 以後のなりすまし検知のためcredentialsへ書き戻して保存する。
     * 接続に成功したWordPressサイトについては、著者(ユーザー)作成に必要な管理者権限の有無も判定する。
     */
    @Transactional
    public SiteConnectionCheckResult checkConnection(Long id) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));
        try {
            Map<String, String> rawCredentials = getRawCredentials(site);
            CmsCredentials credentials = buildCredentialsFromMap(site.getCmsType(), rawCredentials);
            CmsAdapter adapter = cmsAdapterFactory.resolve(site.getCmsType());
            ConnectionCheckResult connectionCheckResult = adapter.testConnection(credentials);

            if (shouldPinHostKeyFingerprint(rawCredentials, connectionCheckResult)) {
                Map<String, String> updated = withObservedHostKeyFingerprint(rawCredentials, connectionCheckResult);
                site.setCredentialsEncrypted(credentialCipher.encrypt(writeCredentialsJson(updated)));
                siteRepository.save(site);
            }

            Boolean hasAdminCapability = resolveHasAdminCapability(connectionCheckResult, site, adapter, credentials, rawCredentials);
            return new SiteConnectionCheckResult(connectionCheckResult.ok(), hasAdminCapability,
                    connectionCheckResult.failureReason(), connectionCheckResult.detail());
        } catch (Exception e) {
            log.warn("疎通確認に失敗しました (siteId={}, siteKey={}): {}", id, site.getSiteKey(), e.getMessage(), e);
            return new SiteConnectionCheckResult(false, null, e.getMessage(), null);
        }
    }

    private static final Set<String> SECRET_CREDENTIAL_KEYS =
            Set.of("appPassword", "sshPrivateKeyPem", "apiKey", "managementApiKey");

    /**
     * サイト管理画面表示用に、現在の設定値を返す。appPassword等のシークレットは値を返さず、
     * どのキーが設定済みかのみをconfiguredSecretFieldsとして返す(ブラウザへ秘密情報を送らないため)。
     */
    @Transactional(readOnly = true)
    public SiteDetailResponse getDetail(Long id) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));

        Map<String, String> visibleCredentials = new LinkedHashMap<>();
        List<String> configuredSecretFields = new ArrayList<>();
        boolean sshConfigured = false;
        try {
            Map<String, String> rawCredentials = getRawCredentials(site);
            sshConfigured = isSshTransport(rawCredentials);
            for (Map.Entry<String, String> entry : rawCredentials.entrySet()) {
                if (!StringUtils.hasText(entry.getValue())) {
                    continue;
                }
                if (SECRET_CREDENTIAL_KEYS.contains(entry.getKey())) {
                    configuredSecretFields.add(entry.getKey());
                } else {
                    visibleCredentials.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            log.warn("サイト設定値の取得に失敗しました (siteId={}, siteKey={}): {}", id, site.getSiteKey(), e.getMessage());
        }

        return SiteDetailResponse.from(site, sshConfigured, visibleCredentials, configuredSecretFields);
    }

    /**
     * SSH接続が設定されているサイトに限り、wp-cliをリモートへインストールする。
     */
    @Transactional(readOnly = true)
    public WpCliInstallResult installWpCli(Long id) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));
        CmsCredentials credentials = buildCredentialsFromMap(site.getCmsType(), getRawCredentials(site));
        CmsAdapter adapter = cmsAdapterFactory.resolve(site.getCmsType());
        return adapter.installWpCli(credentials);
    }

    /**
     * SSH/エージェント(自動構築サイトのwp-cli)接続の場合、hasAuthorProvisioningCapability自体が
     * testConnection相当の再接続を行う実装のため(いずれもREST APIのcreate_users権限に相当する概念がなく、
     * 疎通確認の成功=管理操作可能とみなす設計)、ここで改めて呼ぶと1回の疎通確認で無駄な再接続が発生し、
     * (SSHの場合)共有ホスティングの同時接続数制限等で後続の接続だけがたまたま失敗すると
     * 「管理者権限がない」という紛らわしい結果になる。
     * そのため、SSH/エージェント接続では直前のtestConnectionの成功をそのまま管理者権限ありとみなし、
     * 再接続を避ける。
     */
    private Boolean resolveHasAdminCapability(ConnectionCheckResult connectionCheckResult, Site site,
            CmsAdapter adapter, CmsCredentials credentials, Map<String, String> rawCredentials) {
        if (!connectionCheckResult.ok() || site.getCmsType() != CmsType.WORDPRESS) {
            return null;
        }
        if (isSshTransport(rawCredentials) || isAgentTransport(rawCredentials)) {
            return true;
        }
        return adapter.hasAuthorProvisioningCapability(credentials);
    }

    private void validateCredentials(CmsType cmsType, Map<String, String> credentials) {
        for (String key : requiredCredentialKeys(cmsType, credentials)) {
            if (!StringUtils.hasText(credentials.get(key))) {
                throw new IllegalArgumentException(cmsType.displayName() + ": " + key + " は必須です");
            }
        }
    }

    private List<String> requiredCredentialKeys(CmsType cmsType, Map<String, String> credentials) {
        return switch (cmsType) {
            case WORDPRESS -> isSshTransport(credentials)
                    ? List.of("baseUrl", "transport", "sshHost", "sshUser", "wpPath", "sshPrivateKeyPem")
                    : List.of("baseUrl", "username", "appPassword");
            case MICROCMS -> List.of(
                    "serviceId", "apiKey", "managementApiKey",
                    "postsEndpoint", "categoriesEndpoint", "tagsEndpoint");
        };
    }

    private boolean isSshTransport(Map<String, String> credentials) {
        return "SSH".equalsIgnoreCase(credentials.get("transport"));
    }

    private boolean isAgentTransport(Map<String, String> credentials) {
        return "AGENT".equalsIgnoreCase(credentials.get("transport"));
    }

    private String resolveDisplayBaseUrl(CmsType cmsType, Map<String, String> credentials) {
        return switch (cmsType) {
            case WORDPRESS -> credentials.get("baseUrl");
            case MICROCMS -> "https://" + credentials.get("serviceId") + ".microcms.io";
        };
    }

    private CmsCredentials buildCredentialsFromMap(CmsType cmsType, Map<String, String> credentials) {
        return switch (cmsType) {
            case WORDPRESS -> new CmsCredentials.WordPressCredentials(
                    credentials.get("baseUrl"),
                    credentials.get("username"),
                    credentials.get("appPassword"),
                    credentials.getOrDefault("transport", "REST"),
                    credentials.get("sshHost"),
                    parseSshPort(credentials.get("sshPort")),
                    credentials.get("sshUser"),
                    credentials.get("wpPath"),
                    credentials.get("sshPrivateKeyPem"),
                    credentials.get("sshHostKeyFingerprint"),
                    credentials.get("wpSlug"));
            case MICROCMS -> new CmsCredentials.MicroCmsCredentials(
                    credentials.get("serviceId"),
                    credentials.get("apiKey"),
                    credentials.get("managementApiKey"),
                    credentials.get("postsEndpoint"),
                    credentials.get("categoriesEndpoint"),
                    credentials.get("tagsEndpoint"));
        };
    }

    private Integer parseSshPort(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sshPortは数値で指定してください: " + value);
        }
    }

    private String writeCredentialsJson(Map<String, String> credentials) {
        try {
            return objectMapper.writeValueAsString(credentials);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("認証情報のシリアライズに失敗しました", e);
        }
    }

    private Map<String, String> readCredentialsJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("認証情報のデシリアライズに失敗しました", e);
        }
    }
}
