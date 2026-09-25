package com.letsblog.platform.cli;

import com.letsblog.platform.keycloak.KeycloakAdminClient;
import com.letsblog.platform.keycloak.KeycloakAdminException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * ログイン不能(ロックアウト)時に、対象ユーザーのパスワードを安全にリセットするための運用コマンド。
 * {@code admin-password-reset} プロファイルでのみ有効になり、通常のアプリケーション起動時には動作しない。
 * legacy-apiから移設(issue #693)。使い方は scripts/reset-admin-password.sh 及び
 * docs/COMPREHENSIVE_TROUBLESHOOTING.md を参照。
 *
 * <p>issue #697: {@code @Profile("admin-password-reset")}はこのコンポーネント自体の有効化条件に
 * 過ぎず、Webサーバーの起動有無は制御しない。稼働中のplatformコンテナ(既にポート8080を使用中)へ
 * 追加のプロセスとしてこのプロファイルで起動すると、application.ymlの{@code server.port: 8080}が
 * プロファイルに関わらず固定のままだとポート競合({@code Web server failed to start. Port 8080
 * was already in use.})で失敗する。application.ymlの{@code admin-password-reset}プロファイル専用
 * セクションで{@code server.port: 0}(OSが空いているポートを動的に割り当てる)を指定し、この競合を
 * 回避している({@code spring.main.web-application-type: none}でWebサーバー自体を止める案も検討したが、
 * AuditLogAspect(コンポーネントスキャンで無条件にBean化される)がリクエストスコープの
 * CurrentActorServiceに依存しており、web application contextが無いとBean生成自体に失敗するため
 * 採用しなかった)。
 *
 * <p>issue #681: ログインはKeycloakへ一本化されているため、Keycloak Admin REST API
 * ({@link KeycloakAdminClient})を直接呼び出し、Keycloak上のパスワードを即時変更する
 * (temporary=false)。対象ユーザーがKeycloak上に存在しない場合はエラー終了し(exit code 1)、
 * DBのみを操作して成功したように見せることはない。
 *
 * <p>移設時の設計上の判断(issue #693): legacy-api版はローカルDBのUserService経由で
 * (1)ローカルuserのkeycloak_subキャッシュを先に参照し、無ければKeycloak側をメールアドレスで検索、
 * (2)Keycloak側のパスワード変更後にローカルのpassword_hashも同期更新、という手順だった。しかし
 * password_hashはログインがKeycloakへ一本化されて以降どこからも読み取られておらず
 * (legacy-api内でgetPasswordHash()の呼び出し元が無いことを確認済み)、実際の効果はKeycloak側の
 * パスワード変更のみで完結する。users/rolesテーブルはlegacy-apiが引き続き所有するドメイン
 * (ADR-0004のスキーマ分離により、system_settingsを所有するplatform-serviceからは
 * クロススキーマアクセスできない)であり、緊急復旧用CLIはHTTPリクエストコンテキストを持たず
 * ユーザーの認証済みトークンを転送する経路が無いため、legacy-apiへ内部ブリッジ経由で問い合わせる
 * こともできない。そのため本サービスでは、Keycloakをメールアドレスで検索して直接パスワードを
 * リセットする(ローカルDBの読み書きを一切行わない)、より単純な実装とした。
 */
@Component
@Profile("admin-password-reset")
public class AdminPasswordResetRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordResetRunner.class);

    private final KeycloakAdminClient keycloakAdminClient;
    private final ConfigurableApplicationContext context;

    public AdminPasswordResetRunner(KeycloakAdminClient keycloakAdminClient, ConfigurableApplicationContext context) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = System.getenv("ADMIN_RESET_EMAIL");
        String newPassword = System.getenv("ADMIN_RESET_PASSWORD");

        final int exitCode = resetPassword(email, newPassword);

        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private int resetPassword(String email, String newPassword) {
        if (email == null || email.isBlank() || newPassword == null || newPassword.isBlank()) {
            log.error("環境変数 ADMIN_RESET_EMAIL と ADMIN_RESET_PASSWORD の指定が必要です");
            return 1;
        }
        if (newPassword.length() < 8) {
            log.error("パスワードは8文字以上である必要があります");
            return 1;
        }
        try {
            Optional<String> keycloakSub = keycloakAdminClient.findUserIdByEmail(email);
            if (keycloakSub.isEmpty()) {
                log.error("Keycloak上に該当ユーザーが見つかりません(email={})", email);
                return 1;
            }
            keycloakAdminClient.setPassword(keycloakSub.get(), newPassword);
            log.info("パスワードをリセットしました: {}", email);
            return 0;
        } catch (KeycloakAdminException e) {
            log.error("パスワードのリセットに失敗しました: {}", e.getMessage());
            return 1;
        }
    }
}
