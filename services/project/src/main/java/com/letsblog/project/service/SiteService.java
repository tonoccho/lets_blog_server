package com.letsblog.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.project.aop.AuditLog;
import com.letsblog.project.cms.CmsType;
import com.letsblog.project.cms.ConnectionCheckResult;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.cms.WpCliInstallResult;
import com.letsblog.project.client.CmsProvisioningBridgeClient;
import com.letsblog.project.domain.AuditLogAction;
import com.letsblog.project.domain.Site;
import com.letsblog.project.domain.SshKeyPair;
import com.letsblog.project.dto.SiteConnectionCheckResult;
import com.letsblog.project.dto.SiteDetailResponse;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.dto.SiteUpdateRequest;
import com.letsblog.project.repository.SiteRepository;
import com.letsblog.project.repository.SshKeyPairRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * サイト(WordPress)の登録・更新・認証情報管理を行う(issue #577 stage2、legacy-apiから移設)。
 * サイトのCMS認証情報(暗号化保存)はここが正となる(#577受入基準)。実際のWordPress接続処理
 * (SSH/wp-cliエージェント経由の疎通確認・プロビジョニング)は{@link CmsProvisioningBridgeClient}経由で
 * legacy-apiへ委ねる(PR説明を参照)。
 *
 * <p>SSH鍵ペアの参照は、legacy-apiに残るローカルコピーではなく、本サービス自身の
 * {@link SshKeyPairRepository}(issue #577 stage1で既に移設済み)を直接参照する
 * (stage1のPR説明で予告されていた「SiteService自体をproject-serviceへ移設するstage2で自然に解消される」に対応)。
 */
@Service
@Slf4j
public class SiteService {

    private static final int ADMIN_PATH_MAX_LENGTH = 200;
    private static final Pattern URL_SCHEME_PREFIX = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*:.*", Pattern.DOTALL);
    private static final Pattern WHITESPACE_OR_CONTROL = Pattern.compile(".*[\\s\\p{Cntrl}].*", Pattern.DOTALL);

    private final SiteRepository siteRepository;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;
    private final CmsProvisioningBridgeClient bridgeClient;
    private final ProvisioningService provisioningService;
    private final SshKeyPairRepository sshKeyPairRepository;
    private final CurrentActorService currentActorService;

    public SiteService(SiteRepository siteRepository, CredentialCipher credentialCipher, ObjectMapper objectMapper,
            CmsProvisioningBridgeClient bridgeClient, ProvisioningService provisioningService,
            SshKeyPairRepository sshKeyPairRepository, CurrentActorService currentActorService) {
        this.siteRepository = siteRepository;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
        this.bridgeClient = bridgeClient;
        this.provisioningService = provisioningService;
        this.sshKeyPairRepository = sshKeyPairRepository;
        this.currentActorService = currentActorService;
    }

    /**
     * サイトを登録する。保存前にCMS側へのプロビジョニング(デフォルトカテゴリ・タグ・著者の作成)を実行し、
     * 致命的な失敗の場合は登録自体を行わない。登録後に疎通確認も行うが、こちらは失敗しても登録自体は
     * 取り消さず、結果をレスポンスのconnectionCheckStatusで通知する。
     */
    @AuditLog(action = AuditLogAction.SITE_REGISTERED, resourceType = "SITE")
    @Transactional
    public SiteResponse register(SiteRegisterRequest request) {
        // adminPathは副作用(プロビジョニング・保存)の前に検証する。更新(update)と同じ規則(#1081/#1533)。
        String adminPath = request.adminPath() == null || request.adminPath().isEmpty() ? null : request.adminPath();
        if (adminPath != null) {
            requireRelativePath(adminPath);
        }

        if (siteRepository.existsBySiteKey(request.siteKey())) {
            throw new IllegalArgumentException("siteKey '" + request.siteKey() + "' は既に登録されています");
        }

        validateCredentials(request.cmsType(), request.credentials());

        String actorEmail = currentActorService.getCurrentActorEmail();
        Map<String, String> resolvedCredentials = resolveSshKeyMaterial(request.credentials());

        ProvisioningService.Result provisioningResult =
                provisioningService.provisionSite(request.cmsType().name(), resolvedCredentials, actorEmail);
        if (provisioningResult.categoryError != null || provisioningResult.tagError != null
                || provisioningResult.authorError != null) {
            log.warn("Provisioning partial failure for site '{}': category={}, tag={}, author={}",
                    request.siteKey(), provisioningResult.categoryError, provisioningResult.tagError,
                    provisioningResult.authorError);
        }

        ConnectionCheckResult connectionResult = runConnectionCheck(request.cmsType(), resolvedCredentials);
        Map<String, String> credentialsToStore = shouldPinHostKeyFingerprint(request.credentials(), connectionResult)
                ? withObservedHostKeyFingerprint(request.credentials(), connectionResult)
                : request.credentials();

        Site site = new Site();
        site.setName(request.name());
        site.setSiteKey(request.siteKey());
        site.setCmsType(request.cmsType());
        site.setAdminPath(adminPath);
        site.setBaseUrl(resolveDisplayBaseUrl(request.cmsType(), request.credentials()));
        site.setCredentialsEncrypted(credentialCipher.encrypt(writeCredentialsJson(credentialsToStore)));

        Site saved = siteRepository.save(site);

        return SiteResponse.from(saved, connectionResult.ok());
    }

    /** 既存サイトに対してプロビジョニングを再実行する(管理画面用)。 */
    @Transactional
    public ProvisioningService.Result reprovision(Long siteId) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));

        Map<String, String> credentials = resolveSshKeyMaterial(getRawCredentials(site));
        String actorEmail = currentActorService.getCurrentActorEmail();
        return provisioningService.provisionSite(site.getCmsType().name(), credentials, actorEmail);
    }

    private ConnectionCheckResult runConnectionCheck(CmsType cmsType, Map<String, String> credentialsMap) {
        try {
            return bridgeClient.testConnection(cmsType.name(), credentialsMap);
        } catch (RuntimeException e) {
            log.warn("疎通確認に失敗しました (cmsType={}): {}", cmsType, e.getMessage(), e);
            return ConnectionCheckResult.failure(e.getMessage());
        }
    }

    /**
     * SSHトランスポートのホスト鍵は初回接続時にTOFUで受理される。まだsshHostKeyFingerprintが
     * 保存されておらず、かつ疎通確認が成功してfingerprintが観測された場合のみピン留め対象とする。
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
    public List<SiteResponse> list(String sortBy, String sortOrder) {
        List<Site> sites = siteRepository.findAll();

        if (sortBy != null && !sortBy.isBlank()) {
            sites = sortSites(sites, sortBy, sortOrder);
        }

        return sites.stream().map(SiteResponse::from).toList();
    }

    private List<Site> sortSites(List<Site> sites, String sortBy, String sortOrder) {
        boolean ascending = !"desc".equalsIgnoreCase(sortOrder);

        sites.sort((a, b) -> {
            int result = switch (sortBy) {
                case "name" -> a.getName().compareToIgnoreCase(b.getName());
                case "siteKey" -> a.getSiteKey().compareToIgnoreCase(b.getSiteKey());
                case "cmsType" -> a.getCmsType().toString().compareTo(b.getCmsType().toString());
                case "createdAt" -> a.getCreatedAt().compareTo(b.getCreatedAt());
                case "updatedAt" -> a.getUpdatedAt().compareTo(b.getUpdatedAt());
                default -> 0;
            };
            return ascending ? result : -result;
        });

        return sites;
    }

    @Transactional(readOnly = true)
    public Site getBySiteKey(String siteKey) {
        return siteRepository.findBySiteKey(siteKey)
                .orElseThrow(() -> new SiteNotFoundException("siteKey '" + siteKey + "' は登録されていません"));
    }

    /**
     * 一括管理・環境同期で、非managedサイトをどの経路で扱えるかを判定する(issue #511)。
     */
    @Transactional(readOnly = true)
    public SiteDataSource resolveDataSource(Site site) {
        if (site.isManagedWordpress()) {
            return new SiteDataSource(true, null);
        }
        if (site.getCmsType() != CmsType.WORDPRESS) {
            return new SiteDataSource(false, null);
        }
        try {
            Map<String, String> credentials = getRawCredentials(site);
            if (isSshTransport(credentials)) {
                return new SiteDataSource(false, resolveSshKeyMaterial(credentials));
            }
        } catch (RuntimeException e) {
            log.warn("サイト '{}' の認証情報取得に失敗しました(一括管理の対象外として扱います): {}",
                    site.getSiteKey(), e.getMessage());
        }
        return new SiteDataSource(false, null);
    }

    /** 非managedサイトはSSH接続情報(transport=SSH)が設定されていれば環境同期の対象にできる。 */
    public record SiteDataSource(boolean managed, Map<String, String> sshCredentials) {
        public boolean hasSsh() {
            return sshCredentials != null;
        }

        public boolean isUnavailable() {
            return !managed && sshCredentials == null;
        }
    }

    /** サイトの認証情報を復号し、生のMapとして返す(汎用列を優先し、Phase2以前のレガシー列にフォールバック)。 */
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
     */
    @Transactional
    public SiteResponse update(Long id, SiteUpdateRequest request) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));

        // nameがnullなら名前は変更しない(credentialsのみのpatch)。非nullで空白のみは無言で無視せず拒否する。
        // 変更前(credentialsの更新より前)に検証し、拒否時は何も変更しない。
        if (request.name() != null && !StringUtils.hasText(request.name())) {
            throw new InvalidSiteNameException("サイト名は空にできません");
        }
        // adminPathも変更前に検証する。null=変更しない、""=解除(NULL)、それ以外=検証して設定。
        if (request.adminPath() != null && !request.adminPath().isEmpty()) {
            requireRelativePath(request.adminPath());
        }
        if (request.name() != null) {
            site.setName(request.name());
        }
        if (request.adminPath() != null) {
            site.setAdminPath(request.adminPath().isEmpty() ? null : request.adminPath());
        }

        Boolean connectionOk = null;
        if (request.credentials() != null && !request.credentials().isEmpty()) {
            if (site.isManagedWordpress()) {
                throw new IllegalArgumentException("自動構築されたWordPressサイトの認証情報は編集できません");
            }
            Map<String, String> merged = new HashMap<>(getRawCredentials(site));
            merged.putAll(request.credentials());
            clearSupersededSshKeyMaterial(merged, request.credentials());
            validateCredentials(site.getCmsType(), merged);

            ConnectionCheckResult connectionResult = runConnectionCheck(site.getCmsType(), resolveSshKeyMaterial(merged));
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
     * 管理画面パスは非adminにも見えるリンクhrefになるため、同一オリジン内の相対パスに限定する。
     * 規則はplatform-serviceの{@code AppSettingService#requireRelativePath}(issue #1079)と同一に保つ。
     */
    private void requireRelativePath(String value) {
        if (value.length() > ADMIN_PATH_MAX_LENGTH) {
            throw new InvalidSiteAdminPathException("adminPath は" + ADMIN_PATH_MAX_LENGTH + "文字以内で指定してください");
        }
        if (URL_SCHEME_PREFIX.matcher(value).matches() || value.startsWith("//")) {
            throw new InvalidSiteAdminPathException("adminPath にはURL(スキーム付き・//始まり)ではなく相対パスを指定してください");
        }
        if (WHITESPACE_OR_CONTROL.matcher(value).matches()) {
            throw new InvalidSiteAdminPathException("adminPath に空白文字・制御文字は使用できません");
        }
        if (value.indexOf('\\') >= 0) {
            throw new InvalidSiteAdminPathException("adminPath にバックスラッシュ(\\)は使用できません");
        }
        for (String segment : value.split("/")) {
            if (segment.equals("..")) {
                throw new InvalidSiteAdminPathException("adminPath に「..」のパスセグメントは使用できません");
            }
        }
    }

    /**
     * 既存サイトの疎通確認を再実行する(接続結果自体は永続化しない)。ただしSSHトランスポートで
     * 初回接続のホスト鍵fingerprintが新たに観測された場合のみ保存する。
     */
    @Transactional
    public SiteConnectionCheckResult checkConnection(Long id) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));
        try {
            Map<String, String> rawCredentials = getRawCredentials(site);
            ConnectionCheckResult connectionCheckResult = bridgeClient.testConnection(
                    site.getCmsType().name(), resolveSshKeyMaterial(rawCredentials));

            if (shouldPinHostKeyFingerprint(rawCredentials, connectionCheckResult)) {
                Map<String, String> updated = withObservedHostKeyFingerprint(rawCredentials, connectionCheckResult);
                site.setCredentialsEncrypted(credentialCipher.encrypt(writeCredentialsJson(updated)));
                siteRepository.save(site);
            }

            Boolean hasAdminCapability = resolveHasAdminCapability(connectionCheckResult, site, rawCredentials);
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
     * どのキーが設定済みかのみをconfiguredSecretFieldsとして返す。
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

    /** サイトがSSH transportで設定されているか(環境同期でSSH管理サイトを同期元として扱えるかの判定に使用)。 */
    public boolean isSshConfigured(Site site) {
        try {
            return isSshTransport(getRawCredentials(site));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * サイトのCMS接続情報(sshKeyPairId参照を実際の秘密鍵PEMへ解決済み、すぐ使える状態)を返す
     * (issue #577受入基準: サイトのCMS認証情報の取得APIをpublishing-serviceから利用できるようにする)。
     * {@link com.letsblog.project.controller.SiteCredentialsInternalController}経由で内部ブリッジとして公開する。
     */
    @Transactional(readOnly = true)
    public ResolvedSiteCredentials getResolvedCredentials(String siteKey) {
        Site site = getBySiteKey(siteKey);
        Map<String, String> credentials = resolveSshKeyMaterial(getRawCredentials(site));
        return new ResolvedSiteCredentials(site.getId(), site.getCmsType(), credentials);
    }

    public record ResolvedSiteCredentials(Long siteId, CmsType cmsType, Map<String, String> credentials) {
    }

    /** SSH接続が設定されているサイトに限り、wp-cliをリモートへインストールする。 */
    @Transactional(readOnly = true)
    public WpCliInstallResult installWpCli(Long id) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));
        Map<String, String> credentials = resolveSshKeyMaterial(getRawCredentials(site));
        return bridgeClient.installWpCli(site.getCmsType().name(), credentials);
    }

    /** サイトの letsblog プラグインの導入状態(wp-cliの`wp letsblog status`で判定、issue #1557)。 */
    @Transactional(readOnly = true)
    public LetsblogPluginStatus getLetsblogPluginStatus(Long id) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));
        Map<String, String> credentials = resolveSshKeyMaterial(getRawCredentials(site));
        return bridgeClient.letsblogPluginStatus(site.getCmsType().name(), credentials);
    }

    /** letsblog プラグインを(再)導入し、導入後の状態を返す(issue #1557)。 */
    @Transactional(readOnly = true)
    public LetsblogPluginStatus installLetsblogPlugin(Long id) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));
        Map<String, String> credentials = resolveSshKeyMaterial(getRawCredentials(site));
        return bridgeClient.installLetsblogPlugin(site.getCmsType().name(), credentials);
    }

    private Boolean resolveHasAdminCapability(ConnectionCheckResult connectionCheckResult, Site site,
            Map<String, String> rawCredentials) {
        if (!connectionCheckResult.ok() || site.getCmsType() != CmsType.WORDPRESS) {
            return null;
        }
        if (isSshTransport(rawCredentials) || isAgentTransport(rawCredentials)) {
            return true;
        }
        return bridgeClient.hasAuthorProvisioningCapability(site.getCmsType().name(), rawCredentials);
    }

    private void validateCredentials(CmsType cmsType, Map<String, String> credentials) {
        for (String key : requiredCredentialKeys(cmsType, credentials)) {
            if (!StringUtils.hasText(credentials.get(key))) {
                throw new IllegalArgumentException(cmsType + ": " + key + " は必須です");
            }
        }
        if (cmsType == CmsType.WORDPRESS && isSshTransport(credentials)) {
            validateSshKeyMaterial(credentials);
        }
    }

    private List<String> requiredCredentialKeys(CmsType cmsType, Map<String, String> credentials) {
        return switch (cmsType) {
            case WORDPRESS -> isAgentTransport(credentials)
                    ? List.of("baseUrl", "username", "appPassword")
                    : List.of("baseUrl", "transport", "sshHost", "sshUser", "wpPath");
        };
    }

    /**
     * SSH秘密鍵は「その場で生成しcredentialsへ直接埋め込む(sshPrivateKeyPem)」または
     * 「/admin/ssh-keysで保存済みの名前付き鍵ペアを参照する(sshKeyPairId)」のいずれか一方のみを許可する。
     */
    private void validateSshKeyMaterial(Map<String, String> credentials) {
        boolean hasPrivateKey = StringUtils.hasText(credentials.get("sshPrivateKeyPem"));
        boolean hasKeyPairId = StringUtils.hasText(credentials.get("sshKeyPairId"));
        if (hasPrivateKey == hasKeyPairId) {
            throw new IllegalArgumentException(
                    "SSH秘密鍵は sshPrivateKeyPem(その場で生成) または sshKeyPairId(保存済み鍵ペアの参照) の"
                            + "いずれか一方のみを指定してください");
        }
        if (hasKeyPairId) {
            Long keyPairId = parseSshKeyPairId(credentials.get("sshKeyPairId"));
            if (!sshKeyPairRepository.existsById(keyPairId)) {
                throw new IllegalArgumentException("指定されたSSH鍵ペア(id=" + keyPairId + ")が見つかりません");
            }
        }
    }

    private void clearSupersededSshKeyMaterial(Map<String, String> merged, Map<String, String> patch) {
        if (StringUtils.hasText(patch.get("sshPrivateKeyPem"))) {
            merged.remove("sshKeyPairId");
        } else if (StringUtils.hasText(patch.get("sshKeyPairId"))) {
            merged.remove("sshPrivateKeyPem");
        }
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
        };
    }

    /**
     * legacy-apiへの内部CMSブリッジ({@link CmsProvisioningBridgeClient}）はSSH鍵ペアの保存先
     * ({@link SshKeyPairRepository}、issue #577 stage1でproject-serviceへ移設済み)を持たないため、
     * sshKeyPairIdが指定されている場合は、送信前にこちら側で実際の秘密鍵PEMへ解決してから渡す
     * (保存する値自体は引き続きsshKeyPairId参照のまま。呼び出しのたびに都度解決する)。
     */
    private Map<String, String> resolveSshKeyMaterial(Map<String, String> credentials) {
        String keyPairIdValue = credentials.get("sshKeyPairId");
        if (!StringUtils.hasText(keyPairIdValue) || StringUtils.hasText(credentials.get("sshPrivateKeyPem"))) {
            return credentials;
        }
        Long keyPairId = parseSshKeyPairId(keyPairIdValue);
        SshKeyPair keyPair = sshKeyPairRepository.findById(keyPairId)
                .orElseThrow(() -> new SshKeyPairNotFoundException(
                        "参照先のSSH鍵ペアが見つかりません(id=" + keyPairId + ")"));
        Map<String, String> resolved = new HashMap<>(credentials);
        resolved.put("sshPrivateKeyPem", credentialCipher.decrypt(keyPair.getPrivateKeyEncrypted()));
        return resolved;
    }

    private Long parseSshKeyPairId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sshKeyPairIdは数値で指定してください: " + value);
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
