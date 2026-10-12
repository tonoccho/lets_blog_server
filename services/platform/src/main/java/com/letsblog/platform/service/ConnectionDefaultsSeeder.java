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
 * 起動時に、DBに値が無い場合に限り、コード内の既定値を書き込む仕組みである。既にある値(利用者が設定した値・
 * 受け入れテスト環境が投入したスタブのURL)は上書きしない。値は他のシステム設定と同じく
 * {@link CredentialCipher}で暗号化して保存する(暗号化値はFlywayのSQLでは作れないためJava側で書く)。
 *
 * <p>現在、書き込む既定値は<b>無い</b>。OllamaもComfyUIも同梱せず、GPU PCなど外部のサーバーへURLで接続する
 * ため、既定の接続先は無く、管理画面で設定されるまで「未設定」のままにする(Ollamaは issue #1745、ComfyUIは issue #1746)。
 * 未設定のまま使うと、ai-service / media-service が「接続先が設定されていません」と返す。
 * 既存のDBにある{@code llm_ollama_base_url}(旧既定値の{@code http://ollama:11434/v1}を含む)と
 * {@code comfyui_base_url}(旧既定値の{@code http://comfyui:8188}を含む)は書き換えない(利用者が移行手順で直す)。
 *
 * <p>利用者が管理画面で値を消す(行の削除)と、その値は「未設定」になる。
 */
@Component
public class ConnectionDefaultsSeeder implements ApplicationRunner {

    private final SystemSettingRepository repository;
    private final CredentialCipher credentialCipher;

    public ConnectionDefaultsSeeder(SystemSettingRepository repository, CredentialCipher credentialCipher) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 既定値を持つ接続先は無い(上のJavadoc)。持たせるときだけ、ここへキーと値を足す。
        Map<String, String> defaults = new LinkedHashMap<>();
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
