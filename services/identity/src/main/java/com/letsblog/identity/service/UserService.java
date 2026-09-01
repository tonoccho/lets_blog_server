package com.letsblog.identity.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.identity.domain.Role;
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
 * <p>専用スキーマ lbs_identity(ADR-0004、issue #786)上のusers/rolesテーブルを参照する。
 * 分割前の共有スキーマは issue #785 で削除済み。
 *
 * <p>Keycloakユーザー同期(#562)。ユーザーの作成・削除・無効化/有効化はこのサービスを入口とし、
 * 内部でKeycloak Admin API({@link KeycloakAdminClient})を呼ぶ。ただしkeycloak_subが
 * まだ設定されていないユーザー(=移行スクリプトを未実行のユーザー。現時点では既存ユーザー全員)に
 * 対するupdate/deactivate/reactivate/deleteはKeycloak側に何も存在しないためKeycloak呼び出しを
 * スキップし、ローカルのみを更新する(#561までの既存挙動を変えない)。createは常にKeycloak側の
 * ユーザー作成を伴う(新規ユーザーである以上、Keycloak側に何も無い状態は無い)。
 *
 * <p>パスワード(passwordHash)はこの同期の対象外。現行ログイン(legacy-api)専用の値であり、
 * Keycloakの資格情報とは無関係(ADR-0003のカットオーバー完了までは実ログインで使われ続けるため)。
 *
 * <p><b>admin判定の軸(issue #955、#815の続き)</b>。
 * {@code users.role}(admin/user)が<b>正</b>であり、Keycloakのrealmロール{@code admin}は
 * そこから導出される<b>従</b>である。Web側がセッションのロールをJWTの{@code realm_access.roles}から
 * 導出する(#566)ため、両者が食い違うと「APIは通るのに管理画面へ入れない」状態になる(#955)。
 * そこで{@code users.role}が変わる経路すべてでrealmロールを追随させる。
 * RBAC({@code roles}/{@code user_roles})は3つ目の軸ではなく、admin以外へ個別に権限を配る仕組みで
 * あり、realmロールを駆動しない({@code ROLE_ADMIN}の付与・剥奪はrealmロールを変えず、
 * {@link #reconcileKeycloakAdminRole(Long)}が{@code users.role}に対する冪等な整合だけを行う)。
 * 詳細はdocs/AUTHORIZATION_MATRIX.mdの「admin判定の2つの軸」を参照。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final Set<String> VALID_ROLES = Set.of("admin", "user");

    /** {@code users.role = "admin"} に対応するKeycloakのrealmロール名(infra/keycloak/realm-export.json)。 */
    private static final String KEYCLOAK_ADMIN_REALM_ROLE = "admin";

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

        // RBACのロールは Keycloak へ作りに行く**前**に解決する(issue #956)。
        // マスタデータの欠落は補償のしようがない失敗なので、外部に副作用を出す前に落とす。
        Role defaultRole = resolveDefaultRole(request.role());

        // #562: ユーザー作成はidentity-serviceを入口とし、内部でKeycloakにも登録する。
        // Keycloak側の作成に失敗した場合はローカルにも一切作成しない(暗黙の成功をしない)。
        String keycloakSub = keycloakAdminClient.createUser(request.email(), null, null, false);

        // issue #955: role=adminならKeycloakのrealmロールも付与する(users.roleが正、realmロールが従)。
        // ここで失敗したら孤児となるKeycloakユーザーを消して失敗させる。ローカルだけがadminで
        // Keycloakが追随しない状態(=#955の不具合そのもの)を作らないため、握りつぶさない。
        if (isAdminRole(request.role())) {
            try {
                keycloakAdminClient.grantRealmRole(keycloakSub, KEYCLOAK_ADMIN_REALM_ROLE);
            } catch (RuntimeException e) {
                compensateKeycloakUser(keycloakSub);
                throw e;
            }
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user.setKeycloakSub(keycloakSub);
        user.setEnabled(true);

        if (defaultRole != null) {
            user.getRoles().add(defaultRole);
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
     *
     * <p>#955: Keycloakのrealmロール{@code admin}も併せて付与する。付与に失敗した場合は
     * パスワード設定失敗と同様にKeycloakユーザーを削除して例外にする(ローカルには何も残らない)。
     * 「ログインはできるが管理画面には入れない管理者」を作らないための境界である。
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
            // issue #955: 最初の管理者にKeycloakのrealmロールadminを付与する。これが無いと
            // JWTのrealm_access.rolesにadminが載らず、ログインはできるのに管理画面へ入れない。
            keycloakAdminClient.grantRealmRole(keycloakSub, KEYCLOAK_ADMIN_REALM_ROLE);
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

            Role adminRole = resolveDefaultRole("admin");
            if (adminRole != null) {
                user.getRoles().add(adminRole);
            }

            return UserResponse.from(userRepository.save(user));
        } catch (RuntimeException e) {
            compensateKeycloakUser(keycloakSub);
            throw e;
        }
    }

    /**
     * {@code users.role}(admin/user)に対応するRBACのロールを解決する(issue #956)。
     *
     * <p><b>ロールが見つからないことを正常系として扱わない。</b>
     * 以前は {@code findByRoleName(...).ifPresent(...)} と書いており、
     * {@code roles} テーブルが空でも例外にならず、ユーザー作成だけが成功して
     * ロールが付かない状態が黙って生まれていた。issue #583 で identity-service を
     * 切り出した際にマスタデータの投入が失われ、クリーンな環境では全ユーザーの権限が
     * 空になっていたが、この書き方のせいで誰も気づけなかった。
     *
     * <p>マスタデータは {@code V2__seed_roles_and_permissions.sql} が投入する。
     * ここで見つからないのは設定の欠落であり、作成を続けてよい状況ではない。
     *
     * <p>呼び出し側は Keycloak へユーザーを作る<b>前</b>にこれを呼ぶこと。
     * 外部に副作用を出したあとで落ちると、補償(孤児アカウントの削除)が要る。
     * マスタデータの欠落は補償のしようがない種類の失敗なので、先に落とすほうが素直である。
     *
     * @return 対応するロール。{@code legacyRole} が未知の場合は {@code null}
     * @throws IllegalStateException 対応するロールが {@code roles} に存在しない場合
     */
    private Role resolveDefaultRole(String legacyRole) {
        String defaultRoleName = LEGACY_ROLE_TO_ROLE_NAME.get(legacyRole);
        if (defaultRoleName == null) {
            // 未知のrole文字列。RBACのロールは付けないが、それ自体は呼び出し側の
            // バリデーションの領分なのでここでは何もしない(従来の挙動を保つ)。
            return null;
        }
        return roleRepository.findByRoleName(defaultRoleName)
                .orElseThrow(() -> new IllegalStateException(
                        "RBACのロール '" + defaultRoleName + "' が存在しません。"
                                + "identity-serviceのマイグレーション"
                                + "(V2__seed_roles_and_permissions.sql)が適用されているか確認してください"
                                + "(issue #956)。"));
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

    /**
     * ユーザーを更新する。{@code role}が指定されたら、Keycloakのrealmロールをその値へ追随させる(#955)。
     *
     * <p><b>変化の有無を問わず同期する</b>のは、これが既にずれてしまった環境の<b>回復手段</b>を
     * 兼ねるからである。#955の修正より前に作られた管理者はrealmロールを持たないが、
     * 「変わったときだけ同期する」設計だと、role=adminのユーザーにrole=adminをPATCHしても
     * 何も起こらず、画面からは直せないままになる(このIssueの背景そのもの)。
     * 付与/剥奪はどちらも冪等なので、ずれていなければ実質的に無害な確認で終わる。
     *
     * <p><b>トランザクション境界</b>: Keycloakへの付与/剥奪を<b>ローカル保存より先に</b>行い、
     * 失敗時は{@link KeycloakUserSyncException}をこの{@code @Transactional}メソッドの外へ
     * 伝播させる。ローカルの{@code users.role}はまだ書き換えておらず、保存も呼ばれないうえ、
     * 実行時例外による自動ロールバックで管理下のエンティティへの変更も破棄される。
     * つまり「ローカルだけがadminでKeycloakが追随しない」状態には決してならない
     * (#955の不具合そのものであり、握りつぶしてはならない)。
     *
     * <p>逆向き(Keycloakは成功したがローカル保存が失敗)の場合は、realmロールを元の状態へ
     * ベストエフォートで戻す({@code create}の{@code compensateKeycloakUser}と同じ補償の考え方)。
     * ここで{@code save}ではなく{@code saveAndFlush}を使うのは、この補償を実際に働かせるためである。
     * {@code user}は同一トランザクションで{@code findById}した管理下のエンティティなので、
     * {@code save}(= {@code merge})はUPDATE文の発行をフラッシュまで遅延させる。既定の
     * {@code FlushMode.AUTO}では、後続のクエリが無い以上それはコミット時 — つまり
     * このメソッドのcatchを抜けた後 — になり、DB障害を捕まえられない。
     * {@code create}/{@code setupInitialAdmin}の同じ形のtry/catchが機能するのは、
     * そちらが新規エンティティで、{@code GenerationType.IDENTITY}のid採番のために
     * Hibernateが即座にINSERTを発行するからである。
     *
     * <p>{@code keycloakSub}が未設定のユーザー(未移行)はKeycloak側に対応するアカウントが
     * 無いためスキップする(updateUserProfile/delete/deactivateと同じ扱い)。
     */
    @Transactional
    public UserResponse update(Long id, UserUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        if (request.role() != null) {
            validateRole(request.role());
        }

        boolean wasAdmin = isAdminRole(user.getRole());
        boolean syncedKeycloak = request.role() != null && user.getKeycloakSub() != null;
        if (syncedKeycloak) {
            syncKeycloakAdminRole(user.getKeycloakSub(), isAdminRole(request.role()));
        }

        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        try {
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (RuntimeException e) {
            if (syncedKeycloak) {
                compensateKeycloakAdminRole(user.getKeycloakSub(), wasAdmin);
            }
            throw e;
        }
    }

    /**
     * {@code users.role}を正として、Keycloakのrealmロール{@code admin}を冪等に一致させる(#955)。
     *
     * <p>{@code POST/DELETE /api/users/&#123;userId&#125;/roles/&#123;roleName&#125;}
     * (RBACのロール割り当て)の後始末として呼ぶ。RBACは3つ目のadmin判定軸ではないので、
     * {@code ROLE_ADMIN}が付いたからrealmロールを付ける、という導出はしない。
     * ここで行うのはあくまで{@code users.role}に対する整合であり、何らかの理由で
     * ずれていた場合にそれを直す。ずれていなければKeycloakへの再付与/再剥奪は無害である
     * (どちらも冪等)。
     *
     * <p><b>失敗しても例外にせず、警告ログに留める</b>。他の経路と扱いが違うのは、
     * この経路が{@code users.role}を<b>変えない</b>からである。RBACのロール割り当ては
     * 別トランザクションで既にコミットされており、ここで失敗しても
     * 「ローカルだけがadminでKeycloakが追随しない」新たなずれは生まれない
     * (要件3が禁じているのはそのずれを作って黙ることであり、ここには作りようがない)。
     * 逆に例外にすると、admin性と無関係なRBACロールの付け外しまで、成功しているのに
     * Keycloakの一時的な不調で502を返すことになる。これは修理の機会を1回逃すのと引き換えに、
     * 元々動いていたRBAC管理を壊す取引で、割に合わない。
     * ずれの本格的な修復は{@code PATCH /api/users/&#123;id&#125;}(role指定)が担う。
     */
    @Transactional(readOnly = true)
    public void reconcileKeycloakAdminRole(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("id " + userId + " のユーザーは登録されていません"));
        if (user.getKeycloakSub() == null) {
            return;
        }
        try {
            syncKeycloakAdminRole(user.getKeycloakSub(), isAdminRole(user.getRole()));
        } catch (RuntimeException e) {
            log.warn("ユーザー(id={}, keycloakSub={})のKeycloak realmロール整合に失敗しました。"
                            + "RBACのロール変更自体は成功しています。realmロールのずれが残る場合は"
                            + "PATCH /api/users/{} でroleを指定し直してください(issue #955)。",
                    user.getId(), user.getKeycloakSub(), user.getId(), e);
        }
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
                // issue #955: ここも「Keycloakアカウントが生まれる経路」なので、admin なら
                // realmロールも併せて付与する。付与しないと、移行された管理者が
                // 「ログインはできるが管理画面に入れない」= #955そのものの状態になる。
                // 失敗したら作ったばかりのKeycloakユーザーを消す(createと同じ補償)。消さないと
                // keycloakSubがローカルへ保存されないまま孤児として残り、再実行が409で詰まる。
                // その上でこのユーザーだけを移行失敗として集計する(下のcatch)。他ユーザーは続行する。
                try {
                    syncKeycloakAdminRole(keycloakSub, isAdminRole(user.getRole()));
                } catch (RuntimeException syncFailure) {
                    compensateKeycloakUser(keycloakSub);
                    throw syncFailure;
                }
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

    private boolean isAdminRole(String role) {
        return "admin".equals(role);
    }

    /** Keycloakのrealmロール{@code admin}を、あるべき状態({@code users.role})へ合わせる(#955)。 */
    private void syncKeycloakAdminRole(String keycloakSub, boolean shouldBeAdmin) {
        if (shouldBeAdmin) {
            keycloakAdminClient.grantRealmRole(keycloakSub, KEYCLOAK_ADMIN_REALM_ROLE);
        } else {
            keycloakAdminClient.revokeRealmRole(keycloakSub, KEYCLOAK_ADMIN_REALM_ROLE);
        }
    }

    /**
     * Keycloak側のrealmロール変更に成功した後でローカル保存が失敗した場合の補償(#955)。
     * ローカルはロールバックされるため、Keycloakを元の状態へ戻す。戻せなかった場合は
     * ログに残す(Keycloakだけがadminという状態は、バックエンドの認可がローカルの
     * {@code users.role}を見るため権限昇格にはならないが、放置はしない)。
     */
    private void compensateKeycloakAdminRole(String keycloakSub, boolean previousAdmin) {
        try {
            syncKeycloakAdminRole(keycloakSub, previousAdmin);
        } catch (RuntimeException cleanupFailure) {
            log.error(
                    "ローカル保存失敗後のKeycloak realmロール(sub={})の巻き戻しに失敗しました。手動での確認が必要です。",
                    keycloakSub, cleanupFailure);
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
