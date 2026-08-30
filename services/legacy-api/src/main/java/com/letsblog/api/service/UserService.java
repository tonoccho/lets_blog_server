package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.keycloak.KeycloakAdminClient;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.UserCreateRequest;
import com.letsblog.api.dto.UserResponse;
import com.letsblog.api.repository.RoleRepository;
import com.letsblog.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/**
 * 初回セットアップを扱う。ユーザーのCRUD・プロフィール管理はidentity-serviceに
 * 移設した(#561)。ログイン(パスワード照合・2FA・APIキー発行)はissue #566でKeycloakへ全面移行し
 * 撤去した。このクラスは初回セットアップのためにpassword_hash等の資格情報を引き続き扱う。
 *
 * <p>初回セットアップ(setupInitialAdmin)は、ログイン経路がKeycloakへ一本化された後もローカルDBの
 * password_hash だけを更新しても実際にはログインできないアカウントを作るだけになっていた
 * (#564由来のpre-existingなギャップ、issue #681)。そのため、この操作は{@link KeycloakAdminClient}
 * (Keycloak Admin REST API)を呼び出し、Keycloak上に実際にログイン可能な状態を作る。
 * create等それ以外の操作は本Issueのスコープ外であり、従来どおりローカルDBのみを操作する
 * (Keycloak連携した通常のユーザー作成導線の整備は別Issueで扱う)。
 *
 * <p>誰でも呼び出せたセルフサインアップ(signup)は、
 * ログインがKeycloakへ一本化された後もローカルDBにしかアカウントを作らず、ログインできない
 * ユーザーを生むだけになっていた。Web/拡張/SDK/OpenAPIのいずれからも呼び出されていない
 * 到達不能なコードであったため、issue #688でエンドポイントごと削除した。
 *
 * <p>ロックアウト時の緊急復旧(旧resetPassword)は、issue #693でAdminPasswordResetRunnerごと
 * platform-serviceへ移設された。platform-serviceはこのクラスが参照するusers/rolesテーブル
 * (lets_blogスキーマ)へクロススキーマアクセスできない(ADR-0004)ため、移設後の実装はローカルDBを
 * 一切参照せず、Keycloak Admin APIのメールアドレス検索のみでパスワードを変更する
 * (services/platform/.../cli/AdminPasswordResetRunnerのJavadoc参照。password_hashは
 * ログインがKeycloakへ一本化されて以降どこからも読み取られておらず、実質的な影響はない)。
 *
 * <p>identity-serviceと同一の物理スキーマ(lets_blog)上のusers/rolesテーブルを参照する
 * (ADR-0004が求めるスキーマ分離は将来のIssueで対応する)。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final Set<String> VALID_ROLES = Set.of("admin", "user");

    /**
     * 従来の role 文字列(BFFヘッダ互換用)と、RBACの Role エンティティとの対応。
     * 新規ユーザー作成時のデフォルトロール付与に使う。
     */
    private static final Map<String, String> LEGACY_ROLE_TO_ROLE_NAME = Map.of(
            "admin", "ROLE_ADMIN",
            "user", "ROLE_VIEWER");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CredentialCipher credentialCipher;
    private final KeycloakAdminClient keycloakAdminClient;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            CredentialCipher credentialCipher,
            KeycloakAdminClient keycloakAdminClient) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.credentialCipher = credentialCipher;
        this.keycloakAdminClient = keycloakAdminClient;
    }

    @AuditLog(action = AuditLogAction.USER_CREATED, resourceType = "USER")
    @Transactional
    public UserResponse create(UserCreateRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException("メールアドレス '" + request.email() + "' は既に登録されています");
        }
        validateRole(request.role());

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());

        String defaultRoleName = LEGACY_ROLE_TO_ROLE_NAME.get(request.role());
        if (defaultRoleName != null) {
            roleRepository.findByRoleName(defaultRoleName).ifPresent(role -> user.getRoles().add(role));
        }

        return UserResponse.from(userRepository.save(user));
    }

    /**
     * 内部利用のみ。ArticlePlanServiceからGitHub issue作成時に呼び出す想定。
     */
    @Transactional(readOnly = true)
    public String getDecryptedGithubToken(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));
        if (!user.hasGithubToken()) {
            throw new IllegalStateException("ユーザーの GitHub トークンが設定されていません");
        }
        return credentialCipher.decrypt(user.getGithubTokenEncrypted());
    }

    @Transactional(readOnly = true)
    public boolean hasAnyUser() {
        return userRepository.count() > 0;
    }

    /**
     * usersテーブルが空の場合のみ許可される初回セットアップ。roleは常に"admin"固定。
     *
     * <p>#681: Keycloak上に実際にログイン可能な管理者アカウントを作成する。Keycloak側の
     * ユーザー作成・パスワード即時設定(temporary=false)を先に行い、成功した場合のみローカルにも
     * 作成する(Keycloak側の作成に失敗した場合はローカルには一切作成しない=暗黙に成功しない)。
     * Keycloak側に既に同一email/usernameのユーザーが存在する場合はcreateUserが409で失敗し、
     * このメソッドも例外(KeycloakAdminException、502 Bad Gateway)で失敗する(=拒否)。
     * ローカル保存に失敗した場合は、孤児となったKeycloakユーザーをベストエフォートで削除する。
     */
    @AuditLog(action = AuditLogAction.USER_CREATED, resourceType = "USER")
    @Transactional
    public UserResponse setupInitialAdmin(String email, String password) {
        if (hasAnyUser()) {
            throw new IllegalArgumentException("初回セットアップは既に完了しています");
        }
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("メールアドレス '" + email + "' は既に登録されています");
        }

        String keycloakSub = keycloakAdminClient.createUser(email);
        try {
            keycloakAdminClient.setPassword(keycloakSub, password);
        } catch (RuntimeException e) {
            compensateKeycloakUser(keycloakSub);
            throw e;
        }

        try {
            User user = new User();
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setRole("admin");
            user.setKeycloakSub(keycloakSub);

            String defaultRoleName = LEGACY_ROLE_TO_ROLE_NAME.get("admin");
            if (defaultRoleName != null) {
                roleRepository.findByRoleName(defaultRoleName).ifPresent(role -> user.getRoles().add(role));
            }

            return UserResponse.from(userRepository.save(user));
        } catch (RuntimeException e) {
            compensateKeycloakUser(keycloakSub);
            throw e;
        }
    }

    private void compensateKeycloakUser(String keycloakSub) {
        try {
            keycloakAdminClient.deleteUser(keycloakSub);
        } catch (RuntimeException cleanupFailure) {
            log.error(
                    "ローカル保存失敗後のKeycloakユーザー(sub={})削除にも失敗しました。手動での確認が必要です。",
                    keycloakSub, cleanupFailure);
        }
    }

    private void validateRole(String role) {
        if (!VALID_ROLES.contains(role)) {
            throw new InvalidRoleException("role は 'admin' または 'user' である必要があります");
        }
    }
}
