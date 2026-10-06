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
 * 起動時に、Ollama / ComfyUIの接続先がDBに無い場合だけコード内の既定値を書き込む(issue #1567)。
 * 既にある値は上書きしない。値は他のシステム設定と同じくCredentialCipherで暗号化して保存する。
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
    void DBに2項目とも無ければ既定値を暗号化して書き込む() throws Exception {
        when(repository.findById("llm_ollama_base_url")).thenReturn(Optional.empty());
        when(repository.findById("comfyui_base_url")).thenReturn(Optional.empty());

        seeder().run(null);

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository, times(2)).save(saved.capture());
        List<SystemSetting> rows = saved.getAllValues();
        assertEquals(List.of("llm_ollama_base_url", "comfyui_base_url"),
                rows.stream().map(SystemSetting::getSettingKey).toList());
        assertEquals("http://ollama:11434/v1", cipher.decrypt(rows.get(0).getSettingValueEncrypted()));
        assertEquals("http://comfyui:8188", cipher.decrypt(rows.get(1).getSettingValueEncrypted()));
        assertTrue(!new String(rows.get(0).getSettingValueEncrypted()).contains("ollama"),
                "平文のまま保存してはいけない");
    }

    @Test
    void 既に値がある項目は上書きしない() throws Exception {
        when(repository.findById("llm_ollama_base_url"))
                .thenReturn(Optional.of(row("llm_ollama_base_url", "http://gpu:11434/v1")));
        when(repository.findById("comfyui_base_url"))
                .thenReturn(Optional.of(row("comfyui_base_url", "http://comfyui-stub:8080")));

        seeder().run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void 片方だけ無ければ無い方だけ書き込む() throws Exception {
        when(repository.findById("llm_ollama_base_url"))
                .thenReturn(Optional.of(row("llm_ollama_base_url", "http://gpu:11434/v1")));
        when(repository.findById("comfyui_base_url")).thenReturn(Optional.empty());

        seeder().run(null);

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository).save(saved.capture());
        assertEquals("comfyui_base_url", saved.getValue().getSettingKey());
        assertEquals("http://comfyui:8188", cipher.decrypt(saved.getValue().getSettingValueEncrypted()));
    }

    @Test
    void 値が空白の行は値が無いものとして既定値で埋める() throws Exception {
        when(repository.findById("llm_ollama_base_url"))
                .thenReturn(Optional.of(row("llm_ollama_base_url", "  ")));
        when(repository.findById("comfyui_base_url"))
                .thenReturn(Optional.of(row("comfyui_base_url", "http://comfyui-stub:8080")));

        seeder().run(null);

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository).save(saved.capture());
        assertEquals("llm_ollama_base_url", saved.getValue().getSettingKey());
        assertEquals("http://ollama:11434/v1", cipher.decrypt(saved.getValue().getSettingValueEncrypted()));
    }
}
