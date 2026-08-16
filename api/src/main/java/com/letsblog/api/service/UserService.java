package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.LoginResponse;
import com.letsblog.api.dto.UpdateGithubTokenRequest;
import com.letsblog.api.dto.UpdateUserPreferencesRequest;
import com.letsblog.api.dto.UserCreateRequest;
import com.letsblog.api.dto.UserProfileResponse;
import com.letsblog.api.dto.UserProfileUpdateRequest;
import com.letsblog.api.dto.UserResponse;
import com.letsblog.api.dto.UserUpdateRequest;
import com.letsblog.api.repository.RoleRepository;
import com.letsblog.api.repository.TwoFactorSecretRepository;
import com.letsblog.api.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UserService {

    private static final Set<String> VALID_ROLES = Set.of("admin", "user");

    /**
     * 従来の role 文字列(BFFヘッダ互換用)と、RBACの Role エンティティとの対応。
     * 新規ユーザー作成時のデフォルトロール付与に使う。
     */
    private static final Map<String, String> LEGACY_ROLE_TO_ROLE_NAME = Map.of(
            "admin", "ROLE_ADMIN",
            "user", "ROLE_VIEWER");

    private final UserRepository userRepository;
    private final TwoFactorSecretRepository twoFactorSecretRepository;
    private final RoleRepository roleRepository;
    private final CredentialCipher credentialCipher;
    private final ApiKeyService apiKeyService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(
            UserRepository userRepository,
            TwoFactorSecretRepository twoFactorSecretRepository,
            RoleRepository roleRepository,
            CredentialCipher credentialCipher,
            ApiKeyService apiKeyService) {
        this.userRepository = userRepository;
        this.twoFactorSecretRepository = twoFactorSecretRepository;
        this.roleRepository = roleRepository;
        this.credentialCipher = credentialCipher;
        this.apiKeyService = apiKeyService;
    }

    /**
     * パスワード認証を行う。2FAが有効なユーザーはtwoFactorRequired=trueのみを返し、
     * この時点ではAPIキーを発行しない。呼び出し元はTOTPコード入力を経て
     * /api/auth/totp/verify でログインを完了させ、そこでキーを取得する。
     * 2FAが無効なユーザーはこの時点で認証完了とみなし、APIキーを発行して返す。
     */
    @AuditLog(action = AuditLogAction.LOGIN, resourceType = "USER")
    @Transactional
    public LoginResponse login(String email, String password, String label) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidCredentialsException("メールアドレスまたはパスワードが正しくありません"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException("メールアドレスまたはパスワードが正しくありません");
        }

        boolean twoFactorRequired = twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(user.getId()).isPresent();
        if (twoFactorRequired) {
            return new LoginResponse(UserResponse.from(user), true, null);
        }
        String apiKey = apiKeyService.issue(user, label);
        return new LoginResponse(UserResponse.from(user), false, apiKey);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return userRepository.findAll().stream().map(UserResponse::from).toList();
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

    @AuditLog(action = AuditLogAction.USER_UPDATED, resourceType = "USER")
    @Transactional
    public UserResponse update(Long id, UserUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        if (request.role() != null) {
            validateRole(request.role());
            user.setRole(request.role());
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        return UserResponse.from(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public UserProfileResponse findUserWithProfile(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));
        return UserProfileResponse.from(user);
    }

    @AuditLog(action = AuditLogAction.USER_UPDATED, resourceType = "USER")
    @Transactional
    public UserProfileResponse updateUserProfile(Long id, UserProfileUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setDisplayName(request.displayName());
        user.setNickname(request.nickname());
        user.setWebsiteUrl(request.websiteUrl());
        user.setBio(request.bio());
        user.setLocale(request.locale());
        user.setAvatarUrl(request.avatarUrl());
        user.setDepartment(request.department());
        user.setPosition(request.position());
        user.setSocialLinks(request.socialLinks());
        user.setCustomLinks(request.customLinks());

        return UserProfileResponse.from(userRepository.save(user));
    }

    @AuditLog(action = AuditLogAction.USER_UPDATED, resourceType = "USER")
    @Transactional
    public UserProfileResponse updateUserPreferences(Long id, UpdateUserPreferencesRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        try {
            ZoneId.of(request.timezone());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("不正なタイムゾーンです: " + request.timezone());
        }

        user.setLocale(request.locale());
        user.setTimezone(request.timezone());

        return UserProfileResponse.from(userRepository.save(user));
    }

    @AuditLog(action = AuditLogAction.USER_UPDATED, resourceType = "USER")
    @Transactional
    public UserProfileResponse updateGithubToken(Long id, UpdateGithubTokenRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        byte[] encrypted = credentialCipher.encrypt(request.githubToken());
        user.setGithubTokenEncrypted(encrypted);

        return UserProfileResponse.from(userRepository.save(user));
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

    @AuditLog(action = AuditLogAction.USER_DELETED, resourceType = "USER")
    @Transactional
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new UserNotFoundException("id " + id + " のユーザーは登録されていません");
        }
        userRepository.deleteById(id);
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
     */
    @AuditLog(action = AuditLogAction.USER_CREATED, resourceType = "USER")
    @Transactional
    public UserResponse setupInitialAdmin(String email, String password) {
        if (hasAnyUser()) {
            throw new IllegalArgumentException("初回セットアップは既に完了しています");
        }
        return create(new UserCreateRequest(email, password, "admin"));
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
     */
    @AuditLog(action = AuditLogAction.USER_UPDATED, resourceType = "USER")
    @Transactional
    public UserResponse resetPassword(String email, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("パスワードは8文字以上である必要があります");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("メールアドレス '" + email + "' のユーザーは登録されていません"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        return UserResponse.from(userRepository.save(user));
    }
}
