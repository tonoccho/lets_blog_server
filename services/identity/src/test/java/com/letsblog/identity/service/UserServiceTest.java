package com.letsblog.identity.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.identity.domain.Role;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.MigrationSummaryResponse;
import com.letsblog.identity.dto.ReconciliationSummaryResponse;
import com.letsblog.identity.dto.UpdateGithubTokenRequest;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @Mock
    private UserMigrationPersister userMigrationPersister;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private UserService service() {
        return new UserService(
                userRepository, roleRepository, credentialCipher, keycloakAdminClient, domainEventPublisher,
                userMigrationPersister);
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
        when(keycloakAdminClient.createUser("admin@example.com", null, null, false)).thenReturn("kc-sub-1");
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
        when(keycloakAdminClient.createUser("new@example.com", null, null, false))
                .thenThrow(new KeycloakUserSyncException("Keycloakが停止しています"));

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
        when(keycloakAdminClient.createUser("new@example.com", null, null, false)).thenReturn("kc-sub-2");
        when(userRepository.save(any(User.class))).thenThrow(new RuntimeException("DB書き込み失敗"));

        assertThrows(RuntimeException.class,
                () -> service.create(new UserCreateRequest("new@example.com", "password123", "user")));

        verify(keycloakAdminClient).deleteUser("kc-sub-2");
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
        verify(keycloakAdminClient, never()).createUser(any(), any(), any(), anyBoolean());
        verify(userRepository, never()).save(any(User.class));
    }

    // ------------------------------------------------- Keycloak realmロール同期(issue #955)

    @Test
    void create_role_adminならKeycloakのrealmロールも付与する_issue955() {
        UserService service = service();
        when(userRepository.existsByEmail("admin955@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_ADMIN"))
                .thenReturn(Optional.of(new Role("ROLE_ADMIN", "管理者")));
        when(keycloakAdminClient.createUser("admin955@example.com", null, null, false)).thenReturn("kc-sub-955-1");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new UserCreateRequest("admin955@example.com", "password123", "admin"));

        verify(keycloakAdminClient).grantRealmRole("kc-sub-955-1", "admin");
    }

    @Test
    void create_role_userならrealmロールを付与しない_issue955() {
        UserService service = service();
        when(userRepository.existsByEmail("user955@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_VIEWER"))
                .thenReturn(Optional.of(new Role("ROLE_VIEWER", "閲覧者")));
        when(keycloakAdminClient.createUser("user955@example.com", null, null, false)).thenReturn("kc-sub-955-2");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new UserCreateRequest("user955@example.com", "password123", "user"));

        verify(keycloakAdminClient, never()).grantRealmRole(any(), any());
    }

    @Test
    void create_realmロール付与に失敗したらローカルにも作成せずKeycloakユーザーを消す_issue955() {
        UserService service = service();
        when(userRepository.existsByEmail("admin955@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_ADMIN"))
                .thenReturn(Optional.of(new Role("ROLE_ADMIN", "管理者")));
        when(keycloakAdminClient.createUser("admin955@example.com", null, null, false)).thenReturn("kc-sub-955-3");
        org.mockito.Mockito.doThrow(new KeycloakUserSyncException("realmロールがありません"))
                .when(keycloakAdminClient).grantRealmRole("kc-sub-955-3", "admin");

        assertThrows(KeycloakUserSyncException.class,
                () -> service.create(new UserCreateRequest("admin955@example.com", "password123", "admin")));

        // ローカルDBだけがadminになる状態を作らない(#955の受入基準)。
        verify(userRepository, never()).save(any(User.class));
        verify(keycloakAdminClient).deleteUser("kc-sub-955-3");
    }

    @Test
    void setupInitialAdmin_最初の管理者にrealmロールadminを付与する_issue955() {
        UserService service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("first@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_ADMIN"))
                .thenReturn(Optional.of(new Role("ROLE_ADMIN", "管理者")));
        when(keycloakAdminClient.createUser("first@example.com", null, null, false)).thenReturn("kc-sub-955-4");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.setupInitialAdmin("first@example.com", "password123");

        assertEquals("admin", response.role());
        verify(keycloakAdminClient).setPassword("kc-sub-955-4", "password123");
        verify(keycloakAdminClient).grantRealmRole("kc-sub-955-4", "admin");
    }

    @Test
    void setupInitialAdmin_realmロール付与に失敗したらローカルにも作成しない_issue955() {
        UserService service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("first@example.com")).thenReturn(false);
        when(keycloakAdminClient.createUser("first@example.com", null, null, false)).thenReturn("kc-sub-955-5");
        org.mockito.Mockito.doThrow(new KeycloakUserSyncException("Keycloakが停止しています"))
                .when(keycloakAdminClient).grantRealmRole("kc-sub-955-5", "admin");

        assertThrows(KeycloakUserSyncException.class,
                () -> service.setupInitialAdmin("first@example.com", "password123"));

        verify(userRepository, never()).save(any(User.class));
        verify(keycloakAdminClient).deleteUser("kc-sub-955-5");
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
        verify(keycloakAdminClient).grantRealmRole("kc-sub-955-6", "admin");
        verify(keycloakAdminClient, never()).revokeRealmRole(any(), any());
    }

    @Test
    void update_admin降格でrealmロールを剥奪する_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setRole("admin");
        user.setKeycloakSub("kc-sub-955-7");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.update(1L, new UserUpdateRequest("user", null));

        assertEquals("user", response.role());
        verify(keycloakAdminClient).revokeRealmRole("kc-sub-955-7", "admin");
        verify(keycloakAdminClient, never()).grantRealmRole(any(), any());
    }

    @Test
    void update_roleを指定しなければKeycloakを呼ばない_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-955-8");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(1L, new UserUpdateRequest(null, "newpassword123"));

        verify(keycloakAdminClient, never()).grantRealmRole(any(), any());
        verify(keycloakAdminClient, never()).revokeRealmRole(any(), any());
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

        verify(keycloakAdminClient).grantRealmRole("kc-sub-955-8b", "admin");
    }

    @Test
    void update_keycloakSub未設定ならrealmロールを触らない_issue955() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.update(1L, new UserUpdateRequest("admin", null));

        assertEquals("admin", response.role());
        verify(keycloakAdminClient, never()).grantRealmRole(any(), any());
    }

    @Test
    void update_realmロール付与に失敗したらローカルのroleも変えない_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-955-9");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new KeycloakUserSyncException("Keycloakが停止しています"))
                .when(keycloakAdminClient).grantRealmRole("kc-sub-955-9", "admin");

        assertThrows(KeycloakUserSyncException.class,
                () -> service.update(1L, new UserUpdateRequest("admin", null)));

        // Keycloakを先に呼ぶため、ローカルは書き換えも保存もされない
        // (実際のトランザクションでも例外の伝播でロールバックされる)。
        assertEquals("user", user.getRole());
        verify(userRepository, never()).saveAndFlush(any(User.class));
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

        verify(keycloakAdminClient).grantRealmRole("kc-sub-955-10", "admin");
        verify(keycloakAdminClient).revokeRealmRole("kc-sub-955-10", "admin");
    }

    @Test
    void reconcileKeycloakAdminRole_users_roleがadminなら付与する_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setRole("admin");
        user.setKeycloakSub("kc-sub-955-11");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.reconcileKeycloakAdminRole(1L);

        verify(keycloakAdminClient).grantRealmRole("kc-sub-955-11", "admin");
    }

    @Test
    void reconcileKeycloakAdminRole_users_roleがuserなら剥奪する_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-955-12");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.reconcileKeycloakAdminRole(1L);

        verify(keycloakAdminClient).revokeRealmRole("kc-sub-955-12", "admin");
    }

    @Test
    void reconcileKeycloakAdminRole_keycloakSub未設定なら何もしない_issue955() {
        UserService service = service();
        when(userRepository.findById(1L)).thenReturn(Optional.of(buildUser()));

        service.reconcileKeycloakAdminRole(1L);

        verify(keycloakAdminClient, never()).grantRealmRole(any(), any());
        verify(keycloakAdminClient, never()).revokeRealmRole(any(), any());
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
                null, null);

        UserProfileResponse response = service.updateUserProfile(1L, request);

        assertEquals("太郎", response.firstName());
        assertEquals("山田太郎", response.displayName());
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

        verify(keycloakAdminClient, never()).deleteUser(any());
    }

    @Test
    void delete_keycloakSub設定済みならKeycloak側も削除する() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-3");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.delete(1L);

        verify(keycloakAdminClient).deleteUser("kc-sub-3");
        verify(userRepository).deleteById(1L);
    }

    @Test
    void delete_Keycloak削除に失敗したらローカルも削除しない() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-4");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new KeycloakUserSyncException("Keycloakが停止しています"))
                .when(keycloakAdminClient).deleteUser("kc-sub-4");

        assertThrows(KeycloakUserSyncException.class, () -> service.delete(1L));

        verify(userRepository, never()).deleteById(any());
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
        verify(keycloakAdminClient).setEnabled("kc-sub-5", false);
        verify(domainEventPublisher).publishUserDeactivated(1L, "kc-sub-5");
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
        verify(keycloakAdminClient).setEnabled("kc-sub-6", true);
    }

    @Test
    void migrateToKeycloak_未移行ユーザーを登録しsubを書き戻す() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        when(keycloakAdminClient.createUser("user@example.com", null, null, true)).thenReturn("kc-sub-7");
        // issue #964: migrateToKeycloakはsaveではなくsaveAndFlushを使うようになった。
        // issue #964(レビュー対応): このsaveAndFlushはUserMigrationPersister(独立トランザクション)へ委譲される。
        doNothing().when(userMigrationPersister).saveAndFlush(any(User.class));

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertEquals(List.of(1L), summary.migratedUserIds());
        assertTrue(summary.failedUserIds().isEmpty());
        assertEquals("kc-sub-7", user.getKeycloakSub());
        verify(keycloakAdminClient).sendPasswordResetEmail("kc-sub-7");
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
        when(keycloakAdminClient.createUser("user@example.com", null, null, true)).thenReturn("kc-sub-964-3");
        doNothing().when(userMigrationPersister).saveAndFlush(any(User.class));

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertEquals(List.of(1L), summary.migratedUserIds());
        verify(userMigrationPersister).saveAndFlush(user);
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void migrateToKeycloak_一部失敗しても他は継続する() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        when(keycloakAdminClient.createUser("user@example.com", null, null, true))
                .thenThrow(new KeycloakUserSyncException("Keycloakが停止しています"));

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
        when(keycloakAdminClient.createUser("user@example.com", null, null, true)).thenReturn("kc-sub-955-11");
        doNothing().when(userMigrationPersister).saveAndFlush(any(User.class));

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertEquals(List.of(1L), summary.migratedUserIds());
        verify(keycloakAdminClient).grantRealmRole("kc-sub-955-11", "admin");
    }

    @Test
    void migrateToKeycloak_一般ユーザーにはrealmロールを付与しない_issue955() {
        UserService service = service();
        User user = buildUser();
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        when(keycloakAdminClient.createUser("user@example.com", null, null, true)).thenReturn("kc-sub-955-12");
        doNothing().when(userMigrationPersister).saveAndFlush(any(User.class));

        service.migrateToKeycloak(null);

        verify(keycloakAdminClient, never()).grantRealmRole(any(), any());
    }

    @Test
    void migrateToKeycloak_realmロール付与に失敗したら孤児を消して移行失敗にする_issue955() {
        UserService service = service();
        User user = buildUser();
        user.setRole("admin");
        when(userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(user));
        when(keycloakAdminClient.createUser("user@example.com", null, null, true)).thenReturn("kc-sub-955-13");
        org.mockito.Mockito.doThrow(new KeycloakUserSyncException("Keycloakが停止しています"))
                .when(keycloakAdminClient).grantRealmRole("kc-sub-955-13", "admin");

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.migratedUserIds().isEmpty());
        assertTrue(summary.failedUserIds().containsKey(1L));
        // subを書き戻さないまま残るとKeycloak側が孤児になり、再実行が409で詰まる。
        assertNull(user.getKeycloakSub());
        verify(keycloakAdminClient).deleteUser("kc-sub-955-13");
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
        when(keycloakAdminClient.createUser("user@example.com", null, null, true)).thenReturn("kc-sub-964-1");
        org.mockito.Mockito.doThrow(new RuntimeException("DB書き込み失敗"))
                .when(userMigrationPersister).saveAndFlush(user);

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.migratedUserIds().isEmpty());
        assertTrue(summary.failedUserIds().containsKey(1L));
        verify(keycloakAdminClient).deleteUser("kc-sub-964-1");
        verify(keycloakAdminClient, never()).sendPasswordResetEmail(any());
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
        when(keycloakAdminClient.createUser("user@example.com", null, null, true)).thenReturn("kc-sub-964-2a");
        when(keycloakAdminClient.createUser("other@example.com", null, null, true)).thenReturn("kc-sub-964-2b");
        org.mockito.Mockito.doThrow(new RuntimeException("DB書き込み失敗"))
                .when(userMigrationPersister).saveAndFlush(failingUser);
        doNothing().when(userMigrationPersister).saveAndFlush(succeedingUser);

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.failedUserIds().containsKey(1L));
        assertEquals(List.of(2L), summary.migratedUserIds());
        verify(keycloakAdminClient).deleteUser("kc-sub-964-2a");
        verify(keycloakAdminClient).sendPasswordResetEmail("kc-sub-964-2b");
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
        when(keycloakAdminClient.createUser("user@example.com", null, null, true)).thenReturn("kc-sub-966-1");
        org.mockito.Mockito.doThrow(new KeycloakUserSyncException("SMTPが停止しています"))
                .when(keycloakAdminClient).sendPasswordResetEmail("kc-sub-966-1");

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.migratedUserIds().isEmpty());
        assertTrue(summary.failedUserIds().containsKey(1L));
        // ローカルDBの最終状態はkeycloak_sub未確定に戻す: 次回の対象抽出
        // (findByKeycloakSubIsNull)がこのユーザーを再び拾えるようにするため。
        assertNull(user.getKeycloakSub());
        // 1回目はkeycloak_subを確定させる保存、2回目はメール送信失敗を受けてそれを
        // 取り消す保存(いずれもUserMigrationPersisterへ委譲される)。
        verify(userMigrationPersister, org.mockito.Mockito.times(2)).saveAndFlush(user);
        // 直前に作成したKeycloakユーザーを孤児にしないよう削除する(create/updateと同じ補償)。
        verify(keycloakAdminClient).deleteUser("kc-sub-966-1");
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
        when(keycloakAdminClient.createUser("user@example.com", null, null, true)).thenReturn("kc-sub-966-2");
        org.mockito.Mockito.doThrow(new KeycloakUserSyncException("SMTPが停止しています"))
                .when(keycloakAdminClient).sendPasswordResetEmail("kc-sub-966-2");
        // 1回目(sub確定)は成功、2回目(ロールバック)はDB障害等で失敗する想定。
        org.mockito.Mockito.doNothing()
                .doThrow(new RuntimeException("一時的なDB書き込み失敗"))
                .when(userMigrationPersister).saveAndFlush(user);

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertTrue(summary.migratedUserIds().isEmpty());
        assertTrue(summary.failedUserIds().containsKey(1L));
        verify(keycloakAdminClient).deleteUser("kc-sub-966-2");
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
        org.mockito.Mockito.doThrow(new KeycloakUserSyncException("Keycloakが停止しています"))
                .when(keycloakAdminClient).grantRealmRole("kc-sub-955-14", "admin");

        service.reconcileKeycloakAdminRole(1L);

        verify(keycloakAdminClient).grantRealmRole("kc-sub-955-14", "admin");
    }

    @Test
    void reconcileWithKeycloak_Keycloak側に存在しないユーザーを無効化する() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-8");
        when(userRepository.findByKeycloakSubIsNotNull()).thenReturn(List.of(user));
        when(keycloakAdminClient.exists("kc-sub-8")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReconciliationSummaryResponse summary = service.reconcileWithKeycloak();

        assertEquals(List.of(1L), summary.deactivatedUserIds());
        assertFalse(user.isEnabled());
    }

    @Test
    void reconcileWithKeycloak_Keycloak側に存在すれば何もしない() {
        UserService service = service();
        User user = buildUser();
        user.setKeycloakSub("kc-sub-9");
        when(userRepository.findByKeycloakSubIsNotNull()).thenReturn(List.of(user));
        when(keycloakAdminClient.exists("kc-sub-9")).thenReturn(true);

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

    private User buildUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@example.com");
        user.setRole("user");
        user.setEnabled(true);
        return user;
    }
}
