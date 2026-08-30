package com.letsblog.platform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * アプリ全体で共有するグローバル設定値(プロジェクト/サイトに紐付かないもの)を保持する。
 * 秘匿情報はCredentialCipherでAES-256-GCM暗号化した上でsettingValueEncryptedへ格納する。
 * legacy-apiから移設(issue #693)。lbs_platformスキーマ(ADR-0004)を所有する。
 */
@Entity
@Table(name = "system_settings")
@Getter
@Setter
@NoArgsConstructor
public class SystemSetting {

    @Id
    @Column(name = "setting_key", length = 100)
    private String settingKey;

    @Column(name = "setting_value_encrypted")
    private byte[] settingValueEncrypted;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public SystemSetting(String settingKey, byte[] settingValueEncrypted) {
        this.settingKey = settingKey;
        this.settingValueEncrypted = settingValueEncrypted;
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    void onCreate() {
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
