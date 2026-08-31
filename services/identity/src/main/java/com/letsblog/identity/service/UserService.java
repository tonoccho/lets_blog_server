package com.letsblog.identity.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.MigrationSummaryResponse;
import com.letsblog.identity.dto.ReconciliationSummaryResponse;
import com.letsblog.identity.dto.UpdateGithubTokenRequest;
import com.letsblog.identity.dto.UpdateUserPreferencesRequest;
import com.letsblog.identity.dto.UserCreateRequest;
import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.dto.UserProfileUpdateRequest;
import com.letsblog.identity.dto.UserResponse;
import com.letsblog.identity.dto.UserUpdateRequest;
import com.letsblog.identity.keycloak.KeycloakAdminClient;
import com.letsblog.identity.keycloak.KeycloakUserSyncException;
import com.letsblog.identity.messaging.DomainEventPublisher;
import com.letsblog.identity.repository.RoleRepository;
import com.letsblog.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ユーザーのCRUD・プロフィール管理を担う(#561)。ログイン(パスワード照合・2FA・APIキー発行)は
 * legacy-apiのUserServiceに残したまま(現行の認証機構はカットオーバー計画(#591)を経てから
 * 撤去する方針。ADR-0003参照)であり、このクラスでは扱わない。
 *
 * <p>現時点ではlegacy-apiと同一の物理スキーマ(lets_blog)上のusers/rolesテーブルを
 * 参照する(ADR-0004が求めるスキーマ分離自体は本Issueのスコープ外。両サービスが
 * 同一テーブルを直接参照する状態は移行期間中の暫定措置であり、将来のスキーマ分離
 * Issueで解消する)。
 *
 * <p>Keycloakユーザー同期(#562)。ユーザーの作成・削除・無効化/有効化はこのサービスを入口とし、
 * 内部でKeycloak Admin API({@link KeycloakAdminClient})を呼ぶ。ただしkeycloak_subが
 * まだ設定されていないユーザー(=移行スクリプトを未実行のユーザー。現時点では既存ユーザー全員)に
 * 対するupdate/deactivate/reactivate/deleteはKeycloak側に何も存在しないためKeycloak呼び出しを
 * スキップし、ローカルのみを更新する(#561までの既存挙動を変えない)。createは常にKeycloak側の
 * ユーザー作成を伴う(新規ユーザーである以上、Keycloak側に何も無い状態は無い)。
 *
 * <p>ロール(role/roles)とパスワード(passwordHash)はこの同期の対象外。ロールはアプリ側プロファイルの
 * 一部として引き続きidentity-serviceのみが正であり、passwordHashは現行ログイン(legacy-api)専用の
 * 値でKeycloakの資格情報とは無関係(ADR-0003のカットオーバー完了までは実ログインで使われ続けるため)。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final Set<String> VALID_ROLES = Set.of("admin", "user");

    private static final Map<String, String> LEGACY_ROLE_TO_ROLE_NAME = Map.of(
            "admin", "ROLE_ADMIN",
            "user", "ROLE_VIEWER");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CredentialCipher credentialCipher;
    private final KeycloakAdminClient keycloakAdminClient;
    private final DomainEventPublisher domainEventPublisher;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            CredentialCipher credentialCipher,
            KeycloakAdminClient keycloakAdminClient,
            DomainEventPublisher domainEventPublisher) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.credentialCipher = credentialCipher;
        this.keycloakAdminClient = keycloakAdminClient;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return userRepository.findAll().stream().map(UserResponse::from).toList();
    }

    @Transactional
    public UserResponse create(UserCreateRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException("メールアドレス '" + request.email() + "' は既に登録されています");
        }
        validateRole(request.role());

        // #562: ユーザー作成はidentity-serviceを入口とし、内部でKeycloakにも登録する。
        // Keycloak側の作成に失敗した場合はローカルにも一切作成しない(暗黙の成功をしない)。
        String keycloakSub = keycloakAdminClient.createUser(request.email(), null, null, false);

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user.setKeycloakSub(keycloakSub);
        user.setEnabled(true);

        String defaultRoleName = LEGACY_ROLE_TO_ROLE_NAME.get(request.role());
        if (defaultRoleName != null) {
            roleRepository.findByRoleName(defaultRoleName).ifPresent(role -> user.getRoles().add(role));
        }

        try {
            return UserResponse.from(userRepository.save(user));
        } catch (RuntimeException e) {
            // ローカル保存に失敗した場合、Keycloak側に孤児アカウントを残さないようベストエフォートで削除する。
            compensateKeycloakUser(keycloakSub);
            throw e;
        }
    }

    /**
     * 利用者が1人でも登録されているか。初回セットアップ導線({@code GET /api/auth/setup-status})が使う。
     * issue #583でlegacy-apiから移設した。
     */
    @Transactional(readOnly = true)
    public boolean hasAnyUser() {
        return userRepository.count() > 0;
    }

    /**
     * {@code users}が空の場合のみ許可される初回セットアップ。roleは常に"admin"固定。
     * issue #583でlegacy-apiから移設した。
     *
     * <p>#681: Keycloak上に実際にログイン可能な管理者アカウントを作成する。Keycloak側の
     * ユーザー作成・パスワード即時設定({@code temporary=false})を先に行い、成功した場合のみ
     * ローカルにも作成する(Keycloak側の作成に失敗した場合はローカルには一切作成しない
     * = 暗黙に成功しない)。Keycloak側に既に同一email/usernameのユーザーが存在する場合は
     * {@code createUser}が409で失敗し、このメソッドも例外で失敗する(= 拒否)。
     * ローカル保存に失敗した場合は、孤児となったKeycloakユーザーをベストエフォートで削除する。
     */
    @Transactional
    public UserResponse setupInitialAdmin(String email, String password) {
        if (hasAnyUser()) {
            throw new IllegalArgumentException("初回セットアップは既に完了しています");
        }
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("メールアドレス '" + email + "' は既に登録されています");
        }

        String keycloakSub = keycloakAdminClient.createUser(email, null, null, false);
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
            user.setEnabled(true);

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

    /**
     * 利用者個人のGitHubトークン(暗号化済み)。未設定なら{@code null}。
     * project-service の GitHub アクセス解決が内部ブリッジ経由で使う(issue #583)。
     * 復号は呼び出し元が全サービス共通の{@code APP_ENCRYPTION_KEY}で行う。
     */
    @Transactional(readOnly = true)
    public byte[] getGithubTokenEncrypted(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"))
                .getGithubTokenEncrypted();
    }

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

        // #562: Keycloakに登録済み(keycloak_sub設定済み)のユーザーのみプロフィールを同期する。
        // 未移行ユーザー(現時点では既存ユーザー全員)はKeycloak側に対応するアカウントが無いためスキップする。
        if (user.getKeycloakSub() != null) {
            keycloakAdminClient.updateProfile(user.getKeycloakSub(), request.firstName(), request.lastName());
        }

        return UserProfileResponse.from(userRepository.save(user));
    }

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

    @Transactional
    public UserProfileResponse updateGithubToken(Long id, UpdateGithubTokenRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        byte[] encrypted = credentialCipher.encrypt(request.githubToken());
        user.setGithubTokenEncrypted(encrypted);

        return UserProfileResponse.from(userRepository.save(user));
    }

    @Transactional
    public void delete(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        // #562: Keycloak側にも対応するアカウントがあれば削除する。ローカル削除後もKeycloak側に
        // 有効なアカウントが残る(=誰も管理していないログイン手段が残る)状態を避けるため、
        // Keycloak側の削除に失敗した場合はローカルの削除も中断する(暗黙に成功しない)。
        if (user.getKeycloakSub() != null) {
            keycloakAdminClient.deleteUser(user.getKeycloakSub());
        }
        userRepository.deleteById(id);
    }

    /**
     * ユーザーを無効化する(#562の受入基準: 作成・更新・無効化)。ローカルのenabledをfalseにし、
     * Keycloak側に登録済みであればKeycloak側も無効化する。ハード削除ではないため、
     * ロール・プロフィール等の情報は保持される。
     */
    @Transactional
    public UserResponse deactivate(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        if (user.getKeycloakSub() != null) {
            keycloakAdminClient.setEnabled(user.getKeycloakSub(), false);
        }
        user.setEnabled(false);
        UserResponse response = UserResponse.from(userRepository.save(user));
        // user.deactivatedイベント(letsblog.events、issue #580)。各サービスの権限キャッシュ破棄用。
        domainEventPublisher.publishUserDeactivated(user.getId(), user.getKeycloakSub());
        return response;
    }

    /** {@link #deactivate(Long)}の逆操作。無効化されたユーザーを再度有効化する。 */
    @Transactional
    public UserResponse reactivate(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        if (user.getKeycloakSub() != null) {
            keycloakAdminClient.setEnabled(user.getKeycloakSub(), true);
        }
        user.setEnabled(true);
        return UserResponse.from(userRepository.save(user));
    }

    /**
     * 既存ユーザーをKeycloakへ一括登録する移行スクリプト(#562)。
     * 現行のpassword_hashはKeycloakの資格情報形式と異なり移行できないため、Keycloak側では
     * ランダムな初期パスワードを設定した上でrequiredActions=UPDATE_PASSWORDを要求し、
     * パスワード再設定メールを送信する(一括切り替え方針。ADR-0003参照)。
     *
     * <p>userIdsを指定した場合はそのユーザーのみを対象にする(個別の再実行・段階的な移行に使う)。
     * 省略した場合はkeycloak_sub未設定の全ユーザーが対象になる。既にkeycloak_subが設定済みの
     * ユーザーは(userIdsに含まれていても)スキップし、二重登録しない(再実行が安全なように)。
     *
     * <p>1ユーザーの失敗で全体を中断せず、成功/失敗を集計して返す。
     */
    @Transactional
    public MigrationSummaryResponse migrateToKeycloak(List<Long> userIds) {
        List<User> targets = (userIds == null || userIds.isEmpty())
                ? userRepository.findByKeycloakSubIsNull()
                : userRepository.findAllById(userIds).stream()
                        .filter(user -> user.getKeycloakSub() == null)
                        .toList();

        List<Long> migrated = new ArrayList<>();
        Map<Long, String> failed = new LinkedHashMap<>();

        for (User user : targets) {
            try {
                String keycloakSub = keycloakAdminClient.createUser(
                        user.getEmail(), user.getFirstName(), user.getLastName(), true);
                user.setKeycloakSub(keycloakSub);
                userRepository.save(user);
                keycloakAdminClient.sendPasswordResetEmail(keycloakSub);
                migrated.add(user.getId());
            } catch (KeycloakUserSyncException e) {
                log.warn("ユーザー(id={})のKeycloak移行に失敗しました: {}", user.getId(), e.getMessage());
                failed.put(user.getId(), e.getMessage());
            }
        }

        return new MigrationSummaryResponse(migrated, failed);
    }

    /**
     * 孤児検出(#562)。Keycloak側で削除された(もしくは何らかの理由で存在しなくなった)ユーザーを検出し、
     * ローカル側を論理無効化(enabled=false)する。ハード削除はしない(監査証跡を残すため)。
     * オンデマンド実行(管理者トリガー)であり、自動的な定期実行は現時点では行わない。
     */
    @Transactional
    public ReconciliationSummaryResponse reconcileWithKeycloak() {
        List<User> synced = userRepository.findByKeycloakSubIsNotNull();
        List<Long> deactivated = new ArrayList<>();

        for (User user : synced) {
            if (user.isEnabled() && !keycloakAdminClient.exists(user.getKeycloakSub())) {
                user.setEnabled(false);
                userRepository.save(user);
                deactivated.add(user.getId());
                log.warn("ユーザー(id={}, keycloakSub={})はKeycloak側に存在しないため無効化しました",
                        user.getId(), user.getKeycloakSub());
            }
        }

        return new ReconciliationSummaryResponse(deactivated);
    }

    /**
     * JIT(Just-In-Time) provisioning(#562)。Keycloakのsubに対応するローカルユーザーが無ければ
     * 作成する。emailが一致する既存ユーザー(移行スクリプト未実行でkeycloak_subがまだ無いユーザーを含む)
     * があれば、新規作成せずそのユーザーにsubを紐付ける。
     *
     * <p>現時点ではこのメソッドは実リクエスト経路には配線していない。identity-serviceの
     * CurrentActorServiceはissue #566でKeycloak JWTのsubクレームのみを信頼する実装へ
     * 一本化済みだが、「初回ログイン時にこのメソッドを呼び出す」配線自体は別途必要であり、
     * 本Issueのスコープでは行わない。
     */
    @Transactional
    public UserResponse provisionFromKeycloak(String keycloakSub, String email, String firstName, String lastName) {
        User existingBySub = userRepository.findByKeycloakSub(keycloakSub).orElse(null);
        if (existingBySub != null) {
            return UserResponse.from(existingBySub);
        }

        User existingByEmail = userRepository.findByEmail(email).orElse(null);
        if (existingByEmail != null) {
            existingByEmail.setKeycloakSub(keycloakSub);
            return UserResponse.from(userRepository.save(existingByEmail));
        }

        User user = new User();
        user.setEmail(email);
        user.setKeycloakSub(keycloakSub);
        user.setRole("user");
        user.setEnabled(true);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        // Keycloakがこのユーザーの資格情報の正であり、passwordHashは現行ログイン(legacy-api)専用の
        // 値。JIT作成されたユーザーはlegacy-apiの旧ログインを使わない想定のため、
        // 誰にも知られない乱数値を設定しNOT NULL制約のみ満たす(実際に検証に使われることはない)。
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));

        roleRepository.findByRoleName("ROLE_VIEWER").ifPresent(role -> user.getRoles().add(role));

        return UserResponse.from(userRepository.save(user));
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
