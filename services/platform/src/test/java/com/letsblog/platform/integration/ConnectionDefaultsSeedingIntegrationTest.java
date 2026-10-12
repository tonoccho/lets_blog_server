package com.letsblog.platform.integration;

import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.repository.SystemSettingRepository;
import com.letsblog.platform.service.ConnectionDefaultsSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 起動時の既定値の投入(issue #1567)を、実DB(lbs_platform_test)と実際のSpringコンテキストで確かめる。
 * 起動時に走る{@link ApplicationRunner}として登録されていること、DBに行が無い状態で走らせると既定値が
 * 暗号化されて入り、既にある値は変わらないこと。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("platform-service: Ollama / ComfyUI接続先の起動時既定値投入(issue #1567)")
class ConnectionDefaultsSeedingIntegrationTest {

    @Autowired
    private ConnectionDefaultsSeeder seeder;
    @Autowired
    private SystemSettingRepository repository;
    @Autowired
    private CredentialCipher cipher;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private IdentityClient identityClient;

    private String stored(String key) {
        return repository.findById(key).map(s -> cipher.decrypt(s.getSettingValueEncrypted())).orElse(null);
    }

    @Test
    @DisplayName("起動時に走るApplicationRunnerとして登録されている")
    void 起動時に走る() {
        assertInstanceOf(ApplicationRunner.class, seeder);
    }

    @Test
    @DisplayName("DBに行が無ければOllamaだけ既定値が入り、ComfyUIは書き込まれず(issue #1746)、既にある値は変わらない")
    void 既定値の投入と非上書き() throws Exception {
        repository.deleteById("llm_ollama_base_url");
        repository.deleteById("comfyui_base_url");

        seeder.run(null);

        assertEquals("http://ollama:11434/v1", stored("llm_ollama_base_url"));
        assertNull(stored("comfyui_base_url"));

        // 既存DBにある値(旧既定値 http://comfyui:8188 を含む)は書き換えない。
        repository.save(new com.letsblog.platform.domain.SystemSetting(
                "comfyui_base_url", cipher.encrypt("http://comfyui:8188")));
        seeder.run(null);

        assertEquals("http://comfyui:8188", stored("comfyui_base_url"));

        // 後続のテストへ残さない。
        repository.deleteById("comfyui_base_url");
        seeder.run(null);
    }
}
