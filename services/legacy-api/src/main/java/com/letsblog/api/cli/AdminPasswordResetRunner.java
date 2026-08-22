package com.letsblog.api.cli;

import com.letsblog.api.service.UserService;
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
 * 使い方は scripts/reset-admin-password.sh 及び docs/COMPREHENSIVE_TROUBLESHOOTING.md を参照。
 */
@Component
@Profile("admin-password-reset")
public class AdminPasswordResetRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordResetRunner.class);

    private final UserService userService;
    private final ConfigurableApplicationContext context;

    public AdminPasswordResetRunner(UserService userService, ConfigurableApplicationContext context) {
        this.userService = userService;
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
        try {
            userService.resetPassword(email, newPassword);
            log.info("パスワードをリセットしました: {}", email);
            return 0;
        } catch (RuntimeException e) {
            log.error("パスワードのリセットに失敗しました: {}", e.getMessage());
            return 1;
        }
    }
}
