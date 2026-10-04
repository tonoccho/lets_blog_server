package com.letsblog.project.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.project.domain.Site;
import com.letsblog.project.repository.SiteRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 廃止済みのREST接続方式が使っていた {@code appPassword} を、既存サイトの
 * {@code credentials_encrypted} から取り除く(issue #1565)。
 *
 * <p>認証情報はAES-256-GCMで暗号化されているためFlywayのSQLでは消せず、起動時に復号→削除→再暗号化する。
 * 取り除くものが無ければ何も保存しないので、毎回の起動で実行しても冪等である。
 */
@Component
public class LegacyAppPasswordCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyAppPasswordCleanup.class);
    private static final String APP_PASSWORD_KEY = "appPassword";

    private final SiteRepository siteRepository;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;

    public LegacyAppPasswordCleanup(SiteRepository siteRepository, CredentialCipher credentialCipher,
            ObjectMapper objectMapper) {
        this.siteRepository = siteRepository;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        int cleaned = cleanup();
        if (cleaned > 0) {
            log.info("廃止済みの appPassword を {} 件のサイトの認証情報から削除しました", cleaned);
        }
    }

    /** @return appPasswordを取り除いて保存し直したサイト数 */
    int cleanup() {
        int cleaned = 0;
        for (Site site : siteRepository.findAll()) {
            if (site.getCredentialsEncrypted() == null) {
                continue;
            }
            try {
                Map<String, String> credentials = objectMapper.readValue(
                        credentialCipher.decrypt(site.getCredentialsEncrypted()),
                        new TypeReference<LinkedHashMap<String, String>>() { });
                if (credentials.remove(APP_PASSWORD_KEY) == null) {
                    continue;
                }
                site.setCredentialsEncrypted(credentialCipher.encrypt(objectMapper.writeValueAsString(credentials)));
                siteRepository.save(site);
                cleaned++;
            } catch (Exception e) {
                log.warn("サイト '{}' の認証情報を読めないため appPassword の掃除を飛ばします: {}",
                        site.getSiteKey(), e.getMessage());
            }
        }
        return cleaned;
    }
}
