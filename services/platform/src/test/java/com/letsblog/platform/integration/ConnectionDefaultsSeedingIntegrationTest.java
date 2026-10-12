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
 * 起動時に走る{@link ApplicationRunner}として登録されていること、OllamaもComfyUIも同梱しないので
 * (issue #1745 / #1746)DBに行が無い状態で走らせても何も書き込まず、既にある値は変わらないこと。
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
    @DisplayName("DBに行が無ければOllamaにもComfyUIにも既定値は書き込まれず(issue #1745 / #1746)、既にある値は変わらない")
    void 既定値の投入と非上書き() throws Exception {
        repository.deleteById("llm_ollama_base_url");
        repository.deleteById("comfyui_base_url");

        seeder.run(null);

        assertNull(stored("llm_ollama_base_url"), "同梱Ollamaは無いので接続先は未設定のまま(#1745)");
        assertNull(stored("comfyui_base_url"), "同梱ComfyUIは無いので接続先は未設定のまま(#1746)");

        // 既存DBにある値(旧既定値 http://ollama:11434/v1 / http://comfyui:8188 を含む)は書き換えない。
        repository.save(new com.letsblog.platform.domain.SystemSetting(
                "llm_ollama_base_url", cipher.encrypt("http://ollama:11434/v1")));
        repository.save(new com.letsblog.platform.domain.SystemSetting(
                "comfyui_base_url", cipher.encrypt("http://comfyui:8188")));
        seeder.run(null);

        assertEquals("http://ollama:11434/v1", stored("llm_ollama_base_url"), "既存DBの値は書き換えない");
        assertEquals("http://comfyui:8188", stored("comfyui_base_url"), "既存DBの値は書き換えない");

        // 後続のテストへ残さない。
        repository.deleteById("llm_ollama_base_url");
        repository.deleteById("comfyui_base_url");
    }
}
