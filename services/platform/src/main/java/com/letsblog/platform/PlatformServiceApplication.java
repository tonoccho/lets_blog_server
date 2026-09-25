package com.letsblog.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * platform-service(issue #693、C10-1)。プロジェクト/サイトに紐付かないアプリ全体のグローバル設定
 * (system_settingsテーブル: Brave Search APIキー・実効LLM接続設定/メール送信/Google OAuthクライアント/
 * Webフロント公開URL/アップロードレート制限)をlegacy-apiから抽出したもの。緊急パスワードリセット
 * (AdminPasswordResetRunner)もここへ移設する。lbs_platformスキーマ(ADR-0004)を所有する。
 *
 * <p>scanBasePackagesにcom.letsblog.commonを含めるのは、CredentialCipher(system_settings.
 * setting_value_encryptedの暗号化に使う)・ServiceTokenClient(KeycloakAdminClientが使う)が
 * lbs-commonライブラリのBeanのため(legacy-api/ai-service/analytics-serviceと同じ理由)。
 *
 * <p>{@code @EnableScheduling}は、ContainerStatusBroadcaster/ConnectedServiceStatusBroadcasterの
 * 定期ブロードキャスト({@code @Scheduled(fixedRate = ...)})のために必要(issue #695、C10-3で
 * legacy-apiから移設)。legacy-apiのLetsBlogApiApplicationと同じ構成。
 */
@SpringBootApplication(scanBasePackages = {"com.letsblog.platform", "com.letsblog.common"})
@EnableScheduling
public class PlatformServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformServiceApplication.class, args);
    }
}
