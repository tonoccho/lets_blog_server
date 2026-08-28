package com.letsblog.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * platform-service(issue #693、C10-1)。プロジェクト/サイトに紐付かないアプリ全体のグローバル設定
 * (system_settingsテーブル: Brave Search APIキー・実効LLM接続設定/メール送信/Google OAuthクライアント/
 * Webフロント公開URL/アップロードレート制限)をlegacy-apiから抽出したもの。緊急パスワードリセット
 * (AdminPasswordResetRunner)もここへ移設する。lbs_platformスキーマ(ADR-0004)を所有する。
 *
 * <p>scanBasePackagesにcom.letsblog.commonを含めるのは、CredentialCipher(system_settings.
 * setting_value_encryptedの暗号化に使う)・ServiceTokenClient(KeycloakAdminClientが使う)が
 * lbs-commonライブラリのBeanのため(legacy-api/ai-service/analytics-serviceと同じ理由)。
 */
@SpringBootApplication(scanBasePackages = {"com.letsblog.platform", "com.letsblog.common"})
public class PlatformServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformServiceApplication.class, args);
    }
}
