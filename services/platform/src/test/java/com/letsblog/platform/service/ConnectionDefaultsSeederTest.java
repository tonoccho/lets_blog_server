package com.letsblog.platform.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 起動時に、Ollamaの接続先がDBに無い場合だけコード内の既定値を書き込む(issue #1567)。
 * ComfyUIは同梱しないため書き込まない(issue #1746)。既にある値は上書きしない。値は他のシステム設定と同じくCredentialCipherで暗号化して保存する。
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

    private SystemSetting row(String key, String plain) {
        return new SystemSetting(key, cipher.encrypt(plain));
    }

    @Test
    void DBにOllamaの接続先が無ければ既定値を暗号化して書き込む() throws Exception {
        when(repository.findById("llm_ollama_base_url")).thenReturn(Optional.empty());

        seeder().run(null);

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository).save(saved.capture());
        SystemSetting row = saved.getValue();
        assertEquals("llm_ollama_base_url", row.getSettingKey());
        assertEquals("http://ollama:11434/v1", cipher.decrypt(row.getSettingValueEncrypted()));
        assertTrue(!new String(row.getSettingValueEncrypted()).contains("ollama"),
                "平文のまま保存してはいけない");
    }

    /** issue #1746: ComfyUIは同梱しないので、接続先が未設定でも既定値を書き込まない(読みにも行かない)。 */
    @Test
    void ComfyUIの接続先には既定値を書き込まない() throws Exception {
        when(repository.findById("llm_ollama_base_url")).thenReturn(Optional.empty());

        seeder().run(null);

        verify(repository, never()).findById("comfyui_base_url");
        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, times(1)).save(saved.capture());
        assertEquals(List.of("llm_ollama_base_url"),
                saved.getAllValues().stream().map(SystemSetting::getSettingKey).toList());
    }

    /** issue #1746: 既存DBにある http://comfyui:8188 などの値は書き換えない(=ComfyUI行には触れない)。 */
    @Test
    void 既存のComfyUI接続先は書き換えない() throws Exception {
        when(repository.findById("llm_ollama_base_url"))
                .thenReturn(Optional.of(row("llm_ollama_base_url", "http://gpu:11434/v1")));

        seeder().run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void 値が空白のOllama行は値が無いものとして既定値で埋める() throws Exception {
        when(repository.findById("llm_ollama_base_url"))
                .thenReturn(Optional.of(row("llm_ollama_base_url", "  ")));

        seeder().run(null);

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository).save(saved.capture());
        assertEquals("llm_ollama_base_url", saved.getValue().getSettingKey());
        assertEquals("http://ollama:11434/v1", cipher.decrypt(saved.getValue().getSettingValueEncrypted()));
    }
}
