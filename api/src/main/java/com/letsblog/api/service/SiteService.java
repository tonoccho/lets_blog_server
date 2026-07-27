package com.letsblog.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class SiteService {

    private final SiteRepository siteRepository;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;

    public SiteService(SiteRepository siteRepository, CredentialCipher credentialCipher, ObjectMapper objectMapper) {
        this.siteRepository = siteRepository;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SiteResponse register(SiteRegisterRequest request) {
        if (siteRepository.existsBySiteKey(request.siteKey())) {
            throw new IllegalArgumentException("siteKey '" + request.siteKey() + "' は既に登録されています");
        }

        validateCredentials(request.cmsType(), request.credentials());

        Site site = new Site();
        site.setName(request.name());
        site.setSiteKey(request.siteKey());
        site.setCmsType(request.cmsType());
        site.setBaseUrl(resolveDisplayBaseUrl(request.cmsType(), request.credentials()));
        site.setCredentialsEncrypted(credentialCipher.encrypt(writeCredentialsJson(request.credentials())));

        return SiteResponse.from(siteRepository.save(site));
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
