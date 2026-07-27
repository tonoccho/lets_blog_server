package com.letsblog.api.service;

import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SiteService {

    private final SiteRepository siteRepository;
    private final CredentialCipher credentialCipher;

    public SiteService(SiteRepository siteRepository, CredentialCipher credentialCipher) {
        this.siteRepository = siteRepository;
        this.credentialCipher = credentialCipher;
    }

    @Transactional
    public SiteResponse register(SiteRegisterRequest request) {
        if (siteRepository.existsBySiteKey(request.siteKey())) {
            throw new IllegalArgumentException("siteKey '" + request.siteKey() + "' は既に登録されています");
        }

        Site site = new Site();
        site.setName(request.name());
        site.setSiteKey(request.siteKey());
        site.setBaseUrl(request.baseUrl());
        site.setWpUsername(request.wpUsername());
        site.setWpAppPasswordEncrypted(credentialCipher.encrypt(request.wpAppPassword()));

        return SiteResponse.from(siteRepository.save(site));
    }

    @Transactional(readOnly = true)
    public List<SiteResponse> list() {
        return siteRepository.findAll().stream().map(SiteResponse::from).toList();
    }
}
