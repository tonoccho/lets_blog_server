package com.letsblog.api.config;

import com.letsblog.api.dto.UserCreateRequest;
import com.letsblog.api.repository.UserRepository;
import com.letsblog.api.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * usersテーブルが空の場合のみ、環境変数(INITIAL_ADMIN_EMAIL/INITIAL_ADMIN_PASSWORD)から
 * 管理者アカウントを1件自動作成する。複数人利用開始前のブートストラップ専用処理。
 */
@Component
public class InitialAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminBootstrap.class);

    private final UserRepository userRepository;
    private final UserService userService;
    private final String initialAdminEmail;
    private final String initialAdminPassword;

    public InitialAdminBootstrap(
            UserRepository userRepository,
            UserService userService,
            @Value("${app.initial-admin-email:}") String initialAdminEmail,
            @Value("${app.initial-admin-password:}") String initialAdminPassword
    ) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.initialAdminEmail = initialAdminEmail;
        this.initialAdminPassword = initialAdminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        if (initialAdminEmail.isBlank() || initialAdminPassword.isBlank()) {
            log.warn("INITIAL_ADMIN_EMAIL/INITIAL_ADMIN_PASSWORDが未設定のため、初回管理者アカウントの作成をスキップしました");
            return;
        }

        userService.create(new UserCreateRequest(initialAdminEmail, initialAdminPassword, "admin"));
        log.info("初回管理者アカウント({})を作成しました", initialAdminEmail);
    }
}
