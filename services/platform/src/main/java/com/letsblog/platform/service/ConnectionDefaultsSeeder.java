package com.letsblog.platform.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.SystemSettingRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Ollama / ComfyUIの接続先は環境変数から読まず、DB(system_settings)だけで解決する(issue #1567)。
 * 起動時に、DBに値が無い場合に限り、コード内の既定値を書き込む。既にある値(利用者が設定した値・
 * 受け入れテスト環境が投入したスタブのURL)は上書きしない。値は他のシステム設定と同じく
 * {@link CredentialCipher}で暗号化して保存する(暗号化値はFlywayのSQLでは作れないためJava側で書く)。
 *
 * <p>利用者が管理画面で値を消す(行の削除)と、その時点から次の起動までは「未設定」になる。
 */
@Component
public class ConnectionDefaultsSeeder implements ApplicationRunner {

    static final String DEFAULT_OLLAMA_BASE_URL = "http://ollama:11434/v1";
    static final String DEFAULT_COMFYUI_BASE_URL = "http://comfyui:8188";

    private final SystemSettingRepository repository;
    private final CredentialCipher credentialCipher;

    public ConnectionDefaultsSeeder(SystemSettingRepository repository, CredentialCipher credentialCipher) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put(AppSettingService.LLM_OLLAMA_BASE_URL, DEFAULT_OLLAMA_BASE_URL);
        defaults.put(AppSettingService.COMFYUI_BASE_URL, DEFAULT_COMFYUI_BASE_URL);
        defaults.forEach(this::seedIfAbsent);
    }

    private void seedIfAbsent(String key, String defaultValue) {
        Optional<SystemSetting> existing = repository.findById(key);
        boolean present = existing
                .map(setting -> credentialCipher.decrypt(setting.getSettingValueEncrypted()))
                .filter(value -> !value.isBlank())
                .isPresent();
        if (!present) {
            SystemSetting setting = existing.orElseGet(() -> new SystemSetting(key, null));
            setting.setSettingValueEncrypted(credentialCipher.encrypt(defaultValue));
            repository.save(setting);
        }
    }
}
