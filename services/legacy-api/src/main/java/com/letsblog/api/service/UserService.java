package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.keycloak.KeycloakAdminClient;
import com.letsblog.api.keycloak.KeycloakAdminException;
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
 * セルフサインアップ、初回セットアップを扱う。ユーザーのCRUD・プロフィール管理はidentity-serviceに
 * 移設した(#561)。ログイン(パスワード照合・2FA・APIキー発行)はissue #566でKeycloakへ全面移行し
 * 撤去した。このクラスは初回セットアップ・緊急復旧のためにpassword_hash等の資格情報を引き続き扱う。
 *
 * <p>初回セットアップ(setupInitialAdmin)・緊急復旧(resetPassword、AdminPasswordResetRunnerから
 * 呼び出される)は、ログイン経路がKeycloakへ一本化された後もローカルDBの password_hash だけを
 * 更新しても実際にはログインできないアカウントを作る/操作するだけになっていた(#564由来の
 * pre-existingなギャップ、issue #681)。そのため、この2つの操作は{@link KeycloakAdminClient}
 * (Keycloak Admin REST API)を呼び出し、Keycloak上に実際にログイン可能な状態を作る/更新する。
 * signup/create等それ以外の操作は本Issueのスコープ外であり、従来どおりローカルDBのみを操作する
 * (Keycloak連携した通常のユーザー作成導線の整備は別Issueで扱う)。
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

    /**
     * 誰でも呼び出せるセルフサインアップ。roleは常に"user"固定。
     */
    @AuditLog(action = AuditLogAction.USER_CREATED, resourceType = "USER")
    @Transactional
    public UserResponse signup(String email, String password) {
        return create(new UserCreateRequest(email, password, "user"));
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

    /**
     * ロックアウト時の運用用パスワードリセット(AdminPasswordResetRunnerから呼び出される想定)。
     * メールでのセルフサービスリセットが使えない場合(SMTP未設定など)に、対象ユーザーを
     * 特定して安全にパスワードだけを上書きする。他ユーザーには影響しない。
     *
     * <p>#681: ログインはKeycloakへ一本化されているため、ローカルのpassword_hashではなく
     * Keycloak側のパスワードを即時変更する(temporary=false、次回ログイン時の強制変更なし)。
     * ローカルにkeycloak_subが未設定の場合はメールアドレスでKeycloak側のユーザーを検索して
     * 紐付ける。対象ユーザーがKeycloak上に見つからない場合はKeycloakAdminExceptionで失敗し、
     * DBのみを操作して成功したように見せることはしない。
     */
    @AuditLog(action = AuditLogAction.USER_UPDATED, resourceType = "USER")
    @Transactional
    public UserResponse resetPassword(String email, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("パスワードは8文字以上である必要があります");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("メールアドレス '" + email + "' のユーザーは登録されていません"));

        String keycloakSub = user.getKeycloakSub();
        if (keycloakSub == null || keycloakSub.isBlank()) {
            keycloakSub = keycloakAdminClient.findUserIdByEmail(email)
                    .orElseThrow(() -> new KeycloakAdminException(
                            "Keycloak上に該当ユーザーが見つかりません(email=" + email + ")。"
                                    + "Keycloak未移行のユーザーである可能性があります。"));
            user.setKeycloakSub(keycloakSub);
        }
        keycloakAdminClient.setPassword(keycloakSub, newPassword);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        return UserResponse.from(userRepository.save(user));
    }
}
