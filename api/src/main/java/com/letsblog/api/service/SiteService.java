package com.letsblog.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Site;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

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

        Site site = new Site();
        site.setName(request.name());
        site.setSiteKey(request.siteKey());
        site.setCmsType(request.cmsType());
        site.setBaseUrl(resolveDisplayBaseUrl(request.cmsType(), request.credentials()));
        site.setCredentialsEncrypted(credentialCipher.encrypt(writeCredentialsJson(request.credentials())));

        Site saved = siteRepository.save(site);
        boolean connectionOk = testConnection(request.cmsType(), request.credentials());

        return SiteResponse.from(saved, connectionOk);
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

    private boolean testConnection(CmsType cmsType, Map<String, String> credentialsMap) {
        try {
            CmsCredentials credentials = buildCredentialsFromMap(cmsType, credentialsMap);
            CmsAdapter adapter = cmsAdapterFactory.resolve(cmsType);
            return adapter.testConnection(credentials);
        } catch (Exception e) {
            return false;
        }
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

        // 新規登録サイト(汎用カラム)を優先的に使う
        if (site.getCredentialsEncrypted() != null) {
            Map<String, String> credentials = readCredentialsJson(credentialCipher.decrypt(site.getCredentialsEncrypted()));
            return buildCredentialsFromMap(site.getCmsType(), credentials);
        }

        // 既存WordPressサイト(Phase2以前に登録されたもの)からのフォールバック
        if (site.getWpUsername() != null && site.getWpAppPasswordEncrypted() != null) {
            String appPassword = credentialCipher.decrypt(site.getWpAppPasswordEncrypted());
            return new CmsCredentials.WordPressCredentials(site.getBaseUrl(), site.getWpUsername(), appPassword);
        }

        throw new IllegalStateException("サイト '" + siteKey + "' の認証情報が無効です");
    }

    private void validateCredentials(CmsType cmsType, Map<String, String> credentials) {
        for (String key : requiredCredentialKeys(cmsType)) {
            if (!StringUtils.hasText(credentials.get(key))) {
                throw new IllegalArgumentException(cmsType.displayName() + ": " + key + " は必須です");
            }
        }
    }

    private List<String> requiredCredentialKeys(CmsType cmsType) {
        return switch (cmsType) {
            case WORDPRESS -> List.of("baseUrl", "username", "appPassword");
            case MICROCMS -> List.of(
                    "serviceId", "apiKey", "managementApiKey",
                    "postsEndpoint", "categoriesEndpoint", "tagsEndpoint");
        };
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
                    credentials.get("appPassword"));
            case MICROCMS -> new CmsCredentials.MicroCmsCredentials(
                    credentials.get("serviceId"),
                    credentials.get("apiKey"),
                    credentials.get("managementApiKey"),
                    credentials.get("postsEndpoint"),
                    credentials.get("categoriesEndpoint"),
                    credentials.get("tagsEndpoint"));
        };
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
