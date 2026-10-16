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
import com.letsblog.identity.keycloak.KeycloakUserSyncException;
import com.letsblog.identity.repository.RoleRepository;
import com.letsblog.identity.repository.UserRepository;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.UserDeactivatedEvent;
import com.letsblog.identity.testsupport.FakeKeycloak;
import com.letsblog.identity.testsupport.IdentityWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code UserService}の単体テスト。{@code KeycloakAdminClient}・{@code DomainEventPublisher}・
 * {@code AuditLogService}・{@code UserMigrationPersister}・{@code CurrentActorService}はすべて実物で、
 * 差し替えるのは外部との通信だけ(DBのリポジトリ=Mockito、MQの{@code RabbitTemplate}=Mockito、
 * KeycloakのHTTP=サーバー側フェイク{@link FakeKeycloak})。Keycloakへ何が送られたかは
 * {@link FakeKeycloak#ops()}で、監査ログとドメインイベントは{@code RabbitTemplate}へ渡された
 * 実メッセージで確かめる。
 */
class UserServiceTest {

    private IdentityWorld world;
    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private FakeKeycloak keycloak;
    private CredentialCipher credentialCipher;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
        userRepository = world.userRepository;
        roleRepository = world.roleRepository;
        keycloak = world.keycloak;
        credentialCipher = world.credentialCipher;
        // 保存系は渡されたエンティティをそのまま返す(JPAの既定の振る舞い)。
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private UserService service() {
        return world.userService;
    }

    private void assertOp(String op) {
        assertTrue(keycloak.ops().contains(op), "Keycloakへ送られた操作に " + op + " が無い: " + keycloak.ops());
    }

    private void assertNoOp(String prefix) {
        assertTrue(keycloak.ops(prefix).isEmpty(),
                "Keycloakへ " + prefix + " が送られている: " + keycloak.ops());
    }

    private AuditLogMessage assertAudit(String action, Long userId) {
        List<AuditLogMessage> matched = world.auditMessages().stream()
                .filter(m -> action.equals(m.action()) && userId.equals(m.resourceId())).toList();
        assertEquals(1, matched.size(), "監査ログ " + action + " が1件ではない: " + world.auditMessages());
        return matched.get(0);
    }

    private void assertNoAudit(String action) {
        assertTrue(world.auditMessages().stream().noneMatch(m -> action.equals(m.action())),
                "監査ログ " + action + " が記録されている");
    }

    @Test
    void list_全ユーザーを返す() {
        UserService service = service();
        User user = new User();
        user.setId(1L);
        user.setEmail("a@example.com");
        user.setRole("user");
        when(userRepository.findAll()).thenReturn(List.of(user));

        List<UserResponse> result = service.list();

        assertEquals(1, result.size());
        assertEquals("a@example.com", result.get(0).email());
    }

    @Test
    void create_既存メールは例外() {
        UserService service = service();
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class,
                () -> service.create(new UserCreateRequest("dup@example.com", "password123", "user")));
    }

    @Test
    void create_ROLE_ADMINが自動付与される() {
        UserService service = service();
        Role adminRole = new Role("ROLE_ADMIN", "管理者");
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        keycloak.nextSub("kc-sub-1");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1L);
            return u;
        });

        UserResponse response = service.create(new UserCreateRequest("admin@example.com", "password123", "admin"));

        assertTrue(response.roleNames().contains("ROLE_ADMIN"));
        assertTrue(response.keycloakLinked());
    }

    @Test
    void create_Keycloak作成に失敗したらローカルにも作成しない() {
        UserService service = service();
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        // issue #956: RBACのロールは Keycloak を呼ぶ前に解決するため、ここのスタブが要る。
        when(roleRepository.findByRoleName("ROLE_VIEWER"))
                .thenReturn(Optional.of(new Role("ROLE_VIEWER", "閲覧者")));
        keycloak.fail("POST", "/users", 503);

        assertThrows(KeycloakUserSyncException.class,
                () -> service.create(new UserCreateRequest("new@example.com", "password123", "user")));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void create_ローカル保存に失敗したらKeycloakユーザーを削除する() {
        UserService service = service();
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_VIEWER"))
                .thenReturn(Optional.of(new Role("ROLE_VIEWER", "閲覧者")));
        keycloak.nextSub("kc-sub-2");
        when(userRepository.save(any(User.class))).thenThrow(new RuntimeException("DB書き込み失敗"));

        assertThrows(RuntimeException.class,
                () -> service.create(new UserCreateRequest("new@example.com", "password123", "user")));

        assertOp("delete:kc-sub-2");
    }

    @Test
    void create_RBACのロールが未投入なら作成せずに落ちる_issue956() {
        UserService service = service();
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        // roles テーブルが空の状態。以前は ifPresent で黙って握りつぶし、
        // ロールの付かないユーザーが作られていた(#956)。
        when(roleRepository.findByRoleName("ROLE_VIEWER")).thenReturn(Optional.empty());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.create(new UserCreateRequest("new@example.com", "password123", "user")));
        assertTrue(thrown.getMessage().contains("ROLE_VIEWER"));

        // Keycloakを呼ぶ前に落ちるので、孤児アカウントの補償も要らない。
        assertNoOp("createUser:");
        verify(userRepository, never()).save(any(User.class));
    }

    // ------------------------------------------------- Keycloak realmロール同期(issue #955)

    @Test
    void create_role_adminならKeycloakのrealmロールも付与する_issue955() {
        UserService service = service();
        when(userRepository.existsByEmail("admin955@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_ADMIN"))
                .thenReturn(Optional.of(new Role("ROLE_ADMIN", "管理者")));
        keycloak.nextSub("kc-sub-955-1");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new UserCreateRequest("admin955@example.com", "password123", "admin"));

        assertOp("grant:kc-sub-955-1:admin");
    }

    @Test
    void create_role_userならrealmロールを付与しない_issue955() {
        UserService service = service();
        when(userRepository.existsByEmail("user955@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_VIEWER"))
                .thenReturn(Optional.of(new Role("ROLE_VIEWER", "閲覧者")));
        keycloak.nextSub("kc-sub-955-2");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new UserCreateRequest("user955@example.com", "password123", "user"));

        assertNoOp("grant:");
    }

    @Test
    void create_realmロール付与に失敗したらローカルにも作成せずKeycloakユーザーを消す_issue955() {
        UserService service = service();
        when(userRepository.existsByEmail("admin955@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_ADMIN"))
                .thenReturn(Optional.of(new Role("ROLE_ADMIN", "管理者")));
        keycloak.nextSub("kc-sub-955-3");
        keycloak.fail("POST", "/users/kc-sub-955-3/role-mappings/realm", 503);

        assertThrows(KeycloakUserSyncException.class,
                () -> service.create(new UserCreateRequest("admin955@example.com", "password123", "admin")));

        // ローカルDBだけがadminになる状態を作らない(#955の受入基準)。
        verify(userRepository, never()).save(any(User.class));
        assertOp("delete:kc-sub-955-3");
    }

    @Test
    void setupInitialAdmin_最初の管理者にrealmロールadminを付与する_issue955() {
        UserService service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("first@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_ADMIN"))
                .thenReturn(Optional.of(new Role("ROLE_ADMIN", "管理者")));
        keycloak.nextSub("kc-sub-955-4");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.setupInitialAdmin("first@example.com", "password123");

        assertEquals("admin", response.role());
        assertOp("setPassword:kc-sub-955-4:password123");
        assertOp("grant:kc-sub-955-4:admin");
    }

    @Test
    void setupInitialAdmin_realmロール付与に失敗したらローカルにも作成しない_issue955() {
        UserService service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("first@example.com")).thenReturn(false);
        keycloak.nextSub("kc-sub-955-5");
        keycloak.fail("POST", "/users/kc-sub-955-5/role-mappings/realm", 503);

        assertThrows(KeycloakUserSyncException.class,
                () -> service.setupInitialAdmin("first@example.com", "password123"));

        verify(userRepository, never()).save(any(User.class));
        assertOp("delete:kc-sub-955-5");
    }

    /** issue #1137レビュー対応: role検証の異常系分岐(#642)のカバレッジを補う。 */
    @Test
    void create_不正なroleは例外() {
        UserService service = service();

        InvalidRoleException thrown = assertThrows(InvalidRoleException.class,
                () -> service.create(new UserCreateRequest("bad-role@example.com", "password123", "superadmin")));

        assertTrue(thrown.getMessage().contains("admin"));
        assertNoOp("createUser:");
    }

    /** issue #1137レビュー対応: setupInitialAdmin の二重セットアップ拒否(L159/L179)のカバレッジを補う。 */
    @Test
    void setupInitialAdmin_利用者が既にいれば例外() {
        UserService service = service();
        when(userRepository.count()).thenReturn(1L);

        assertThrows(IllegalArgumentException.class,
                () -> service.setupInitialAdmin("second@example.com", "password123"));

        assertNoOp("createUser:");
    }

    /** issue #1137レビュー対応: setupInitialAdmin のメール重複拒否(L182)のカバレッジを補う。 */
    @Test
    void setupInitialAdmin_メールが既に登録済みなら例外() {
        UserService service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("dup-first@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class,
                () -> service.setupInitialAdmin("dup-first@example.com", "password123"));

        assertNoOp("createUser:");
    }

    /**
     * issue #1137レビュー対応: {@code resolveDefaultRole}の「未知のroleにはRBACロールを付けない」
     * 分岐(L239)のカバレッジを補う。現行の公開APIはすべて{@code validateRole}で
     * "admin"/"user"以外を先に拒否するため、この分岐に公開経路からは到達できない
     * (private呼び出しでのみ再現できる、意図された防御的分岐)。
     */
    @Test
    void resolveDefaultRole_未知のroleはnullを返す() throws Exception {
        UserService service = service();
        java.lang.reflect.Method method =
                UserService.class.getDeclaredMethod("resolveDefaultRole", String.class);
        method.setAccessible(true);

        Object result = method.invoke(service, "unknown-legacy-role");

        assertNull(result);
        verify(roleRepository, never()).findByRoleName(any());
    }

    @Test
    void update_admin昇格でrealmロールを付与する_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-955-6");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.update(1L, new UserUpdateRequest("admin", null));

        assertEquals("admin", response.role());
        assertOp("grant:kc-sub-955-6:admin");
        assertNoOp("revoke:");
        // issue #1137: role変更が監査ログを記録し、変更前後のroleを渡す。
        AuditLogMessage roleAudit = assertAudit("USER_ROLE_UPDATED", 1L);
        assertTrue(roleAudit.changes().contains("\"from\":\"user\"") && roleAudit.changes().contains("\"to\":\"admin\""),
                roleAudit.changes());
    }

    @Test
    void update_admin降格でrealmロールを剥奪する_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setRole("admin");
        user.setKeycloakSub("kc-sub-955-7");
        keycloak.alreadyAssigned("kc-sub-955-7", "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.update(1L, new UserUpdateRequest("user", null));

        assertEquals("user", response.role());
        assertOp("revoke:kc-sub-955-7:admin");
        assertNoOp("grant:");
    }

    @Test
    void update_roleを指定しなければKeycloakを呼ばない_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-955-8");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, new UserUpdateRequest(null, "newpassword123"));

        assertNoOp("grant:");
        assertNoOp("revoke:");
        // issue #1137: role指定が無い更新(パスワードのみ等)は監査ログを記録しない。
        assertNoAudit("USER_ROLE_UPDATED");
    }

    /**
     * #955の修正より前に作られた管理者は、ローカルがadminのままrealmロールを持たない。
     * 「admin性が変わったときだけ同期する」設計だとこれを画面から直せないので、
     * roleが指定されていれば変化の有無を問わず同期する(冪等なので再付与は無害)。
     */
    @Test
    void update_同じroleを指定し直すとrealmロールを付け直す_ずれの回復手段_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setRole("admin");
        user.setKeycloakSub("kc-sub-955-8b");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, new UserUpdateRequest("admin", null));

        assertOp("grant:kc-sub-955-8b:admin");
    }

    @Test
    void update_keycloakSub未設定ならrealmロールを触らない_issue955() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.update(1L, new UserUpdateRequest("admin", null));

        assertEquals("admin", response.role());
        assertNoOp("grant:");
    }

    /** issue #1137レビュー対応: password が空文字の場合は更新しない分岐(L313)のカバレッジを補う。 */
    @Test
    void update_passwordが空文字なら更新しない() {
        UserService service = service();
        User user = buildUser();
        String originalHash = user.getPasswordHash();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, new UserUpdateRequest(null, ""));

        assertEquals(originalHash, user.getPasswordHash());
    }

    /**
     * issue #1137レビュー対応: ローカル保存失敗時、role未指定(syncedKeycloak=false)なら
     * Keycloakの補償を行わない分岐(L321)のカバレッジを補う。
     */
    @Test
    void update_role未指定でローカル保存に失敗してもKeycloak補償は行わない() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-955-16");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new RuntimeException("DB書き込み失敗"));

        assertThrows(RuntimeException.class,
                () -> service.update(1L, new UserUpdateRequest(null, "newpassword123")));

        assertNoOp("grant:");
        assertNoOp("revoke:");
    }

    @Test
    void update_realmロール付与に失敗したらローカルのroleも変えない_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-955-9");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        keycloak.fail("POST", "/users/kc-sub-955-9/role-mappings/realm", 503);

        assertThrows(KeycloakUserSyncException.class,
                () -> service.update(1L, new UserUpdateRequest("admin", null)));

        // Keycloakを先に呼ぶため、ローカルは書き換えも保存もされない
        // (実際のトランザクションでも例外の伝播でロールバックされる)。
        assertEquals("user", user.getRole());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        // issue #1137: 保存まで到達していないので監査ログも記録しない。
        assertNoAudit("USER_ROLE_UPDATED");
    }

    /**
     * 保存は{@code save}ではなく{@code saveAndFlush}でなければならない。{@code user}は同一
     * トランザクションで読んだ管理下のエンティティなので、{@code save}(= merge)はUPDATEの発行を
     * コミット時まで遅らせる。その場合DB障害はこのメソッドのcatchを抜けた後に起きるため、
     * 下の「ローカル保存に失敗したらrealmロールを元に戻す」補償が働かない(#955のレビュー指摘)。
     */
    @Test
    void update_保存はフラッシュを伴う_補償が働く前提_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-955-15");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, new UserUpdateRequest("admin", null));

        verify(userRepository).saveAndFlush(user);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void update_ローカル保存に失敗したらrealmロールを元に戻す_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-955-10");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new RuntimeException("DB書き込み失敗"));

        assertThrows(RuntimeException.class, () -> service.update(1L, new UserUpdateRequest("admin", null)));

        assertOp("grant:kc-sub-955-10:admin");
        assertOp("revoke:kc-sub-955-10:admin");
    }

    @Test
    void reconcileKeycloakAdminRole_users_roleがadminなら付与する_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setRole("admin");
        user.setKeycloakSub("kc-sub-955-11");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.reconcileKeycloakAdminRole(1L);

        assertOp("grant:kc-sub-955-11:admin");
    }

    @Test
    void reconcileKeycloakAdminRole_users_roleがuserなら剥奪する_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-955-12");
        keycloak.alreadyAssigned("kc-sub-955-12", "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.reconcileKeycloakAdminRole(1L);

        assertOp("revoke:kc-sub-955-12:admin");
    }

    @Test
    void reconcileKeycloakAdminRole_keycloakSub未設定なら何もしない_issue955() {
        UserService service = service();
        when(userRepository.findById(1L)).thenReturn(Optional.of(buildUser()));

        service.reconcileKeycloakAdminRole(1L);

        assertNoOp("grant:");
        assertNoOp("revoke:");
    }

    @Test
    void findUserWithProfile_存在しないユーザーは例外() {
        UserService service = service();
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service.findUserWithProfile(99L));
    }

    @Test
    void updateUserProfile_プロフィール更新完了() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileUpdateRequest request = new UserProfileUpdateRequest(
                "太郎", "山田", "山田太郎", "taro",
                "https://example.com", "自己紹介", "ja_JP",
                "https://gravatar.com/avatar/xxx", "開発部", "エンジニア",
                null, null, null);

        UserProfileResponse response = service.updateUserProfile(1L, request);

        assertEquals("太郎", response.firstName());
        assertEquals("山田太郎", response.displayName());
        assertEquals("user@example.com", response.email());
        verify(userRepository, never()).existsByEmail(any());
        assertNoOp("updateEmail:");
    }

    private UserProfileUpdateRequest emailRequest(String email) {
        return new UserProfileUpdateRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, email);
    }

    @Test
    void updateUserProfile_メールアドレスをDBに反映する_issue1192() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = service.updateUserProfile(1L, emailRequest("  new@example.com "));

        assertEquals("new@example.com", response.email());
        assertEquals("new@example.com", user.getEmail());
        assertNoOp("updateEmail:");
    }

    @Test
    void updateUserProfile_Keycloak登録済みならemailとusernameを追随させる_issue1192() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-1");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateUserProfile(1L, emailRequest("new@example.com"));

        assertOp("updateEmail:kc-sub-1:new@example.com");
    }

    @Test
    void updateUserProfile_使用済みメールアドレスは拒否しDBもKeycloakも変更しない_issue1192() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-1");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class,
                () -> service.updateUserProfile(1L, emailRequest("taken@example.com")));

        assertEquals("user@example.com", user.getEmail());
        assertNoOp("updateEmail:");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void updateUserProfile_現在と同じメールアドレスは重複チェックもKeycloak更新もしない_issue1192() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-1");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateUserProfile(1L, emailRequest("user@example.com"));

        verify(userRepository, never()).existsByEmail(any());
        assertNoOp("updateEmail:");
    }

    @Test
    void updateUserProfile_空白のメールアドレスは変更なしとして扱う_issue1192() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateUserProfile(1L, emailRequest("   "));

        assertEquals("user@example.com", user.getEmail());
        verify(userRepository, never()).existsByEmail(any());
    }

    /** issue #1137レビュー対応: keycloakSub設定済みならKeycloak側も同期する分岐(L401)のカバレッジを補う。 */
    @Test
    void updateUserProfile_keycloakSub設定済みならKeycloak側も同期する() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-profile-1");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileUpdateRequest request = new UserProfileUpdateRequest(
                "太郎", "山田", "山田太郎", "taro",
                "https://example.com", "自己紹介", "ja_JP",
                "https://gravatar.com/avatar/xxx", "開発部", "エンジニア",
                null, null, null);

        service.updateUserProfile(1L, request);

        assertOp("updateProfile:kc-sub-profile-1:太郎:山田");
    }

    @Test
    void updateGithubToken_暗号化して保存される() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = service.updateGithubToken(1L, new UpdateGithubTokenRequest("ghp_dummy"));

        assertTrue(response.githubTokenConfigured());
        assertEquals("ghp_dummy", credentialCipher.decrypt(user.getGithubTokenEncrypted()));
    }

    @Test
    void delete_存在しないユーザーは例外() {
        UserService service = service();
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service.delete(99L));
    }

    @Test
    void delete_keycloakSub未設定ならKeycloakを呼ばない() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.delete(1L);

        assertNoOp("delete:");
    }

    @Test
    void delete_keycloakSub設定済みならKeycloak側も削除する() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-3");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.delete(1L);

        assertOp("delete:kc-sub-3");
        verify(userRepository).deleteById(1L);
    }

    /** issue #1137: ユーザー削除が監査ログを記録する。 */
    @Test
    void delete_監査ログを記録する_issue1137() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.delete(1L);

        assertAudit("USER_DELETED", 1L);
    }

    @Test
    void delete_Keycloak削除に失敗したらローカルも削除しない() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-4");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        keycloak.fail("DELETE", "/users/kc-sub-4", 503);

        assertThrows(KeycloakUserSyncException.class, () -> service.delete(1L));

        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void deactivate_存在しないユーザーは例外() {
        UserService service = service();
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service.deactivate(99L));
        assertNoAudit("USER_DEACTIVATED");
    }

    @Test
    void deactivate_ローカルとKeycloak双方を無効化する() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-5");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.deactivate(1L);

        assertFalse(response.enabled());
        assertOp("setEnabled:kc-sub-5:false");
        assertEquals(1, world.domainEvents().size());
        UserDeactivatedEvent event = (UserDeactivatedEvent) world.domainEvents().get(0);
        assertEquals(1L, event.userId());
        assertEquals("kc-sub-5", event.keycloakSub());
    }

    /** issue #1137: ユーザー無効化が監査ログを記録する。 */
    @Test
    void deactivate_監査ログを記録する_issue1137() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-5b");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.deactivate(1L);

        assertAudit("USER_DEACTIVATED", 1L);
    }

    @Test
    void deactivate_keycloakSub未設定ならKeycloakを呼ばないが監査ログは記録する_issue1137() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.deactivate(1L);

        assertNoOp("setEnabled:");
        assertAudit("USER_DEACTIVATED", 1L);
    }

    @Test
    void reactivate_存在しないユーザーは例外() {
        UserService service = service();
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service.reactivate(99L));
        assertNoAudit("USER_REACTIVATED");
    }

    @Test
    void reactivate_ローカルとKeycloak双方を有効化する() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-6");
        user.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.reactivate(1L);

        assertTrue(response.enabled());
        assertOp("setEnabled:kc-sub-6:true");
    }

    /** issue #1137: ユーザー再有効化が監査ログを記録する。 */
    @Test
    void reactivate_監査ログを記録する_issue1137() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-6b");
        user.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.reactivate(1L);

        assertAudit("USER_REACTIVATED", 1L);
    }

    @Test
    void reactivate_keycloakSub未設定ならKeycloakを呼ばないが監査ログは記録する_issue1137() {
        UserService service = service();
        User user = buildUser();
        user.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.reactivate(1L);

        assertNoOp("setEnabled:");
        assertAudit("USER_REACTIVATED", 1L);
    }

    @Test
    void migrateToKeycloak_未移行ユーザーを登録しsubを書き戻す() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        keycloak.nextSub("kc-sub-7");

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertEquals(List.of(1L), summary.migratedUserIds());
        assertTrue(summary.failedUserIds().isEmpty());
        assertEquals("kc-sub-7", user.getKeycloakSub());
        assertOp("sendPasswordResetEmail:kc-sub-7");
    }

    /**
     * issue #1137レビュー対応: userIds を明示指定した場合の対象絞り込み分岐(L501/L504)の
     * カバレッジを補う。既にkeycloak_sub設定済みのユーザーは(userIdsに含まれていても)
     * 対象から除外される。
     */
    @Test
    void migrateToKeycloak_userIds指定時は未移行のみ対象にする() {
        UserService service = service();
        User notMigrated = buildUser();
        notMigrated.setId(1L);
        User alreadyMigrated = buildUser();
        alreadyMigrated.setId(2L);
        alreadyMigrated.setEmail("already@example.com");
        alreadyMigrated.setKeycloakSub("kc-sub-already");
        when(userRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(notMigrated, alreadyMigrated));
        keycloak.nextSub("kc-sub-migrated");

        MigrationSummaryResponse summary = service.migrateToKeycloak(List.of(1L, 2L));

        assertEquals(List.of(1L), summary.migratedUserIds());
        assertNoOp("createUser:already@example.com");
    }

    /**
     * issue #1137レビュー対応: userIds に空リストを渡した場合の分岐(L501)のカバレッジを補う。
     * {@code userIds == null || userIds.isEmpty()} は空リストでも真になるため、
     * 全件対象({@code findByKeycloakSubIsNull()})と同じ経路を辿る。
     */
    @Test
    void migrateToKeycloak_userIdsが空リストなら全件対象と同じ経路になる() {
        UserService service = service();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of());

        MigrationSummaryResponse summary = service.migrateToKeycloak(List.of());

        assertTrue(summary.migratedUserIds().isEmpty());
        assertTrue(summary.failedUserIds().isEmpty());
        verify(userRepository).findByKeycloakSubIsNull();
        verify(userRepository, never()).findAllById(any());
    }

    /**
     * issue #964(レビュー対応): 同一トランザクション内で複数ユーザーのsaveAndFlushを続けて呼ぶと、
     * 1件の失敗が他のユーザーの永続化コンテキストへ波及しうる(レビュー指摘)。これを構造的に
     * 断つため、1ユーザー分のローカル保存は{@link UserMigrationPersister}(REQUIRES_NEWの
     * 独立トランザクション)へ委譲し、{@code migrateToKeycloak}自身は{@code userRepository.saveAndFlush}
     * を直接呼ばない。Mockitoでは実際のHibernateセッション分離までは検証できないため、ここでは
     * 「委譲先が確かにUserMigrationPersisterであり、migrateToKeycloak自身はsaveAndFlushを呼ばない」
     * という構造を検証する(実DBでのトランザクション境界の検証はIssue本文に記載の理由により
     * 本環境では実施できない)。
     */
    @Test
    void migrateToKeycloak_ローカル保存はUserMigrationPersisterへ委譲される_issue964() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        keycloak.nextSub("kc-sub-964-3");

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertEquals(List.of(1L), summary.migratedUserIds());
        // 1ユーザー分のローカル保存は実物のUserMigrationPersister(REQUIRES_NEWの独立トランザクション)を
        // 経由し、リポジトリのsaveAndFlushが1回だけ呼ばれる。トランザクション属性そのものは
        // UserMigrationPersisterTestが検証する。
        verify(userRepository, times(1)).saveAndFlush(user);
    }

    @Test
    void migrateToKeycloak_一部失敗しても他は継続する() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        keycloak.fail("POST", "/users", 503);

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.migratedUserIds().isEmpty());
        assertTrue(summary.failedUserIds().containsKey(1L));
    }

    @Test
    void migrateToKeycloak_adminならrealmロールも付与する_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setRole("admin");
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        keycloak.nextSub("kc-sub-955-11");

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertEquals(List.of(1L), summary.migratedUserIds());
        assertOp("grant:kc-sub-955-11:admin");
    }

    @Test
    void migrateToKeycloak_一般ユーザーにはrealmロールを付与しない_issue955() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        keycloak.nextSub("kc-sub-955-12");

        service.migrateToKeycloak(null);

        assertNoOp("grant:");
    }

    @Test
    void migrateToKeycloak_realmロール付与に失敗したら孤児を消して移行失敗にする_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setRole("admin");
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        keycloak.nextSub("kc-sub-955-13");
        keycloak.fail("POST", "/users/kc-sub-955-13/role-mappings/realm", 503);

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.migratedUserIds().isEmpty());
        assertTrue(summary.failedUserIds().containsKey(1L));
        // subを書き戻さないまま残るとKeycloak側が孤児になり、再実行が409で詰まる。
        assertNull(user.getKeycloakSub());
        assertOp("delete:kc-sub-955-13");
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * issue #964: {@code save}(=merge、管理下エンティティのUPDATEをフラッシュまで遅延させる)
     * ではなく{@code saveAndFlush}を使うことで、ローカル保存の失敗をこのユーザーのtry/catchの
     * 中で検知する(#955のupdateと同じ理由)。検知できたら、直前に作成したKeycloakユーザーを
     * 孤児にしないよう削除する(create/updateと同じ補償)。
     */
    @Test
    void migrateToKeycloak_ローカル保存に失敗したらKeycloakユーザーを削除する_issue964() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        keycloak.nextSub("kc-sub-964-1");
        doThrow(new RuntimeException("DB書き込み失敗"))
                .when(userRepository).saveAndFlush(user);

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.migratedUserIds().isEmpty());
        assertTrue(summary.failedUserIds().containsKey(1L));
        assertOp("delete:kc-sub-964-1");
        assertNoOp("sendPasswordResetEmail:");
    }

    /**
     * issue #964: 1ユーザーのローカル保存失敗は、そのユーザーを移行失敗として記録した上で
     * バッチ全体を中断せず、他のユーザーの移行は続行する(既存のKeycloakUserSyncExceptionの
     * 扱いと一貫させる。要件3の明示的な決定: 継続)。
     */
    @Test
    void migrateToKeycloak_ローカル保存の失敗は他ユーザーの移行を妨げない_issue964() {
        UserService service = service();
        User failingUser = buildUser();
        User succeedingUser = new User();
        succeedingUser.setId(2L);
        succeedingUser.setEmail("other@example.com");
        succeedingUser.setRole("user");
        succeedingUser.setEnabled(true);

        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(failingUser, succeedingUser));
        keycloak.nextSub("kc-sub-964-2a");
        keycloak.nextSub("kc-sub-964-2b");
        doThrow(new RuntimeException("DB書き込み失敗"))
                .when(userRepository).saveAndFlush(failingUser);

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.failedUserIds().containsKey(1L));
        assertEquals(List.of(2L), summary.migratedUserIds());
        assertOp("delete:kc-sub-964-2a");
        assertOp("sendPasswordResetEmail:kc-sub-964-2b");
    }

    /**
     * issue #966: パスワード再設定メール送信のみが失敗した場合、createUser・realmロール付与・
     * ローカル保存(saveAndFlush)はいずれも成功し、ローカルDBにkeycloak_subが確定した
     * 「後」でメール送信が失敗する。旧実装ではこの確定を取り消さないまま「失敗」として
     * 報告していたため、targets(findByKeycloakSubIsNull)から除外されて再実行不能に
     * なっていた(このユーザーは実際にはKeycloak側は完全に移行済み)。
     *
     * <p>この決定では、メール送信の失敗を検知したら他の失敗経路(realmロール付与失敗・
     * ローカル保存失敗)と同じ形に揃える: ローカルのkeycloak_subを取り消して保存し直し
     * (2回目のsaveAndFlush)、直前に作成したKeycloakユーザーも削除する。これにより
     * 「失敗」と報告される内容とローカルDBの状態(keycloak_sub未確定)が一致し(要件1)、
     * 次回のmigrateToKeycloak呼び出しがこのユーザーを自動的に再度対象にする(要件2)。
     */
    @Test
    void migrateToKeycloak_メール送信のみ失敗したらローカルのsub確定を取り消して移行失敗にする_issue966() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        keycloak.nextSub("kc-sub-966-1");
        keycloak.fail("PUT", "/users/kc-sub-966-1/execute-actions-email", 503);

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.migratedUserIds().isEmpty());
        assertTrue(summary.failedUserIds().containsKey(1L));
        // ローカルDBの最終状態はkeycloak_sub未確定に戻す: 次回の対象抽出
        // (findByKeycloakSubIsNull)がこのユーザーを再び拾えるようにするため。
        assertNull(user.getKeycloakSub());
        // 1回目はkeycloak_subを確定させる保存、2回目はメール送信失敗を受けてそれを
        // 取り消す保存(いずれもUserMigrationPersisterへ委譲される)。
        verify(userRepository, times(2)).saveAndFlush(user);
        // 直前に作成したKeycloakユーザーを孤児にしないよう削除する(create/updateと同じ補償)。
        assertOp("delete:kc-sub-966-1");
    }

    /**
     * issue #966(レビュー対応): メール送信失敗を受けたロールバック処理自体
     * (keycloak_subを未確定へ戻すための2回目のsaveAndFlush)が、一時的なDB障害等で
     * それ自身失敗することがありうる。この2回目のsaveAndFlushが投げる例外は
     * KeycloakUserSyncExceptionではない一般のRuntimeExceptionであり、
     * 外側のcatch(KeycloakUserSyncException e)では捕まえられないため、
     * 何も対処しなければmigrateToKeycloak全体から例外が伝播し、このユーザー以降の
     * 全ユーザーの移行結果ごとバッチ呼び出し全体が中断してしまう
     * (compensateKeycloakUser自身のdeleteUser呼び出しが自己防御されているのと非対称)。
     *
     * <p>この決定では、ロールバックのsaveAndFlush自体の失敗も自己防御し、
     * ログに残した上でこのユーザーを失敗として記録し、バッチ全体は中断せず
     * 通常のサマリを返す。
     */
    @Test
    void migrateToKeycloak_ロールバックの保存自体が失敗してもバッチ全体は中断しない_issue966() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        keycloak.nextSub("kc-sub-966-2");
        keycloak.fail("PUT", "/users/kc-sub-966-2/execute-actions-email", 503);
        // 1回目(sub確定)は成功、2回目(ロールバック)はDB障害等で失敗する想定。
        doReturn(user)
                .doThrow(new RuntimeException("一時的なDB書き込み失敗"))
                .when(userRepository).saveAndFlush(user);

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.migratedUserIds().isEmpty());
        assertTrue(summary.failedUserIds().containsKey(1L));
        assertOp("delete:kc-sub-966-2");
    }

    /**
     * この経路はusers.roleを変えないため、失敗しても「ローカルだけがadmin」という
     * #955のずれは生まれない。例外にするとadmin性と無関係なRBACロールの付け外しまで
     * 502になるので、警告ログに留めて成功を返す。
     */
    @Test
    void reconcileKeycloakAdminRole_同期に失敗しても例外にしない_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setRole("admin");
        user.setKeycloakSub("kc-sub-955-14");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        keycloak.fail("POST", "/users/kc-sub-955-14/role-mappings/realm", 503);

        service.reconcileKeycloakAdminRole(1L);

        // 付与は試みられ(Keycloakが503を返した)、例外にはならない。
        assertOp("failed:POST:/users/kc-sub-955-14/role-mappings/realm");
        assertNoOp("grant:");
    }

    @Test
    void reconcileWithKeycloak_Keycloak側に存在しないユーザーを無効化する() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-8");
        when(userRepository.findByKeycloakSubIsNotNull()).thenReturn(List.of(user));
        keycloak.userMissing("kc-sub-8");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReconciliationSummaryResponse summary = service.reconcileWithKeycloak();

        assertEquals(List.of(1L), summary.deactivatedUserIds());
        assertFalse(user.isEnabled());
    }

    /**
     * issue #1137レビュー対応: 既に無効化済みのユーザーはKeycloakへの存在確認自体を
     * スキップする短絡評価の分岐(L550)のカバレッジを補う。
     */
    @Test
    void reconcileWithKeycloak_既に無効化済みならKeycloakへ問い合わせない() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-disabled");
        user.setEnabled(false);
        when(userRepository.findByKeycloakSubIsNotNull()).thenReturn(List.of(user));

        ReconciliationSummaryResponse summary = service.reconcileWithKeycloak();

        assertTrue(summary.deactivatedUserIds().isEmpty());
        assertNoOp("exists:");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void reconcileWithKeycloak_Keycloak側に存在すれば何もしない() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-9");
        when(userRepository.findByKeycloakSubIsNotNull()).thenReturn(List.of(user));
        
        ReconciliationSummaryResponse summary = service.reconcileWithKeycloak();

        assertTrue(summary.deactivatedUserIds().isEmpty());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void provisionFromKeycloak_sub一致で既存ユーザーを返す() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-10");
        when(userRepository.findByKeycloakSub("kc-sub-10")).thenReturn(Optional.of(user));

        UserResponse response = service.provisionFromKeycloak("kc-sub-10", "user@example.com", "太郎", "山田");

        assertEquals(1L, response.id());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void provisionFromKeycloak_email一致で既存ユーザーにsubを紐付ける() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSub("kc-sub-11")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.provisionFromKeycloak("kc-sub-11", "user@example.com", "太郎", "山田");

        assertEquals("kc-sub-11", user.getKeycloakSub());
        assertTrue(response.keycloakLinked());
    }

    @Test
    void provisionFromKeycloak_該当ユーザーが無ければ新規作成する() {
        UserService service = service();
        when(userRepository.findByKeycloakSub("kc-sub-12")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("new-jit@example.com")).thenReturn(Optional.empty());
        when(roleRepository.findByRoleName("ROLE_VIEWER")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(42L);
            return u;
        });

        UserResponse response = service.provisionFromKeycloak("kc-sub-12", "new-jit@example.com", "花子", "鈴木");

        assertEquals(42L, response.id());
        assertEquals("new-jit@example.com", response.email());
        assertTrue(response.keycloakLinked());
        assertTrue(response.enabled());
    }

    /**
     * issue #1259: 個人設定のタイムゾーンは任意の上書きであり、{@code null}を渡すと
     * 「ブラウザのタイムゾーンに従う(未設定)」へ戻せる。{@code ZoneId.of(null)}は
     * {@code NullPointerException}を投げるため、{@code null}を特別扱いしないと
     * 不正な値と区別できず400になってしまう(要件5)。
     */
    @Test
    void updateUserPreferences_timezoneにnullを渡すと未設定に戻せる_issue1259() {
        UserService service = service();
        User user = buildUser();
        user.setTimezone("Asia/Tokyo");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = service.updateUserPreferences(1L, new UpdateUserPreferencesRequest("ja", null));

        assertNull(response.timezone());
        assertNull(user.getTimezone());
    }

    /** 不正な値(空文字を含む)は従来どおり400相当の例外にする。 */
    @Test
    void updateUserPreferences_不正なタイムゾーンは例外になる_issue1259() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateUserPreferences(1L, new UpdateUserPreferencesRequest("ja", "Not/AZone")));
    }

    private User buildUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@example.com");
        user.setRole("user");
        user.setEnabled(true);
        return user;
    }
}
