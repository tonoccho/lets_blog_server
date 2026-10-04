package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.project.domain.Site;
import com.letsblog.project.repository.SiteRepository;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 廃止済みREST接続のappPasswordを既存のcredentials_encryptedから取り除く掃除(issue #1565)。 */
@ExtendWith(MockitoExtension.class)
class LegacyAppPasswordCleanupTest {

    @Mock
    private SiteRepository siteRepository;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            Base64.getEncoder().encodeToString(new byte[32]));
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 旧データ掃除_credentialsからappPasswordだけを取り除いて再暗号化する_issue1565() {
        Site withSecret = new Site();
        withSecret.setSiteKey("managed");
        withSecret.setCredentialsEncrypted(credentialCipher.encrypt(
                "{\"baseUrl\":\"http://wordpress/sites/m\",\"transport\":\"AGENT\","
                        + "\"username\":\"admin\",\"appPassword\":\"old\",\"wpSlug\":\"m\"}"));
        Site clean = new Site();
        clean.setSiteKey("ssh");
        byte[] cleanBytes = credentialCipher.encrypt("{\"transport\":\"SSH\"}");
        clean.setCredentialsEncrypted(cleanBytes);
        Site noCredentials = new Site();
        noCredentials.setSiteKey("none");
        when(siteRepository.findAll()).thenReturn(java.util.List.of(withSecret, clean, noCredentials));

        int cleaned = new LegacyAppPasswordCleanup(siteRepository, credentialCipher, objectMapper).cleanup();

        assertEquals(1, cleaned);
        verify(siteRepository).save(withSecret);
        verify(siteRepository, never()).save(clean);
        verify(siteRepository, never()).save(noCredentials);
        String plain = credentialCipher.decrypt(withSecret.getCredentialsEncrypted());
        assertTrue(!plain.contains("appPassword"), plain);
        assertTrue(plain.contains("\"username\":\"admin\"") && plain.contains("\"wpSlug\":\"m\""), plain);
        assertArrayEquals(cleanBytes, clean.getCredentialsEncrypted());
    }

    @Test
    void 旧データ掃除_復号できないcredentialsは飛ばして続行する_issue1565() {
        Site broken = new Site();
        broken.setSiteKey("broken");
        broken.setCredentialsEncrypted(new byte[] {1, 2, 3});
        when(siteRepository.findAll()).thenReturn(java.util.List.of(broken));

        assertEquals(0, new LegacyAppPasswordCleanup(siteRepository, credentialCipher, objectMapper).cleanup());
        verify(siteRepository, never()).save(any());
    }

    @Test
    void run_掃除対象が無くても対象があっても例外なく終える_issue1565() throws Exception {
        LegacyAppPasswordCleanup cleanup = new LegacyAppPasswordCleanup(siteRepository, credentialCipher, objectMapper);
        when(siteRepository.findAll()).thenReturn(java.util.List.of());
        cleanup.run(null);
        verify(siteRepository, never()).save(any());

        Site withSecret = new Site();
        withSecret.setSiteKey("managed");
        withSecret.setCredentialsEncrypted(credentialCipher.encrypt("{\"appPassword\":\"old\"}"));
        when(siteRepository.findAll()).thenReturn(java.util.List.of(withSecret));
        cleanup.run(null);
        verify(siteRepository).save(withSecret);
    }
}
