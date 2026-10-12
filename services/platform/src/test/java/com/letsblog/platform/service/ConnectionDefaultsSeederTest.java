package com.letsblog.platform.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 起動時に書き込む接続先の既定値は無い。OllamaもComfyUIも同梱せず(issue #1745 / #1746)、接続先は
 * 管理者が設定するまで未設定のままにする。DBの既存の値(旧既定値を含む)には触れない。
 */
@ExtendWith(MockitoExtension.class)
class ConnectionDefaultsSeederTest {

    @Mock
    private SystemSettingRepository repository;

    private final CredentialCipher cipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private ConnectionDefaultsSeeder seeder() {
        return new ConnectionDefaultsSeeder(repository, cipher);
    }

    @Test
    void DBに何も無くてもOllamaにもComfyUIにも既定値を書き込まず読みにも行かない() throws Exception {
        seeder().run(null);

        // issue #1745: 同梱のOllamaは無い。issue #1746: 同梱のComfyUIは無い。
        verify(repository, never()).findById("llm_ollama_base_url");
        verify(repository, never()).findById("comfyui_base_url");
        verify(repository, never()).save(any(SystemSetting.class));
        verifyNoInteractions(repository);
    }
}
