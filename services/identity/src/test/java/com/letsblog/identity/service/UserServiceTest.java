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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private UserService service() {
        return new UserService(
                userRepository, roleRepository, credentialCipher, keycloakAdminClient, domainEventPublisher);
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
        when(keycloakAdminClient.createUser("new@example.com", null, null, false)).thenReturn("kc-sub-2");
        when(userRepository.save(any(User.class))).thenThrow(new RuntimeException("DB書き込み失敗"));

        assertThrows(RuntimeException.class,
                () -> service.create(new UserCreateRequest("new@example.com", "password123", "user")));

        verify(keycloakAdminClient).deleteUser("kc-sub-2");
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
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MigrationSummaryResponse summary = service.migrateToKeycloak(null);

        assertEquals(List.of(1L), summary.migratedUserIds());
        assertTrue(summary.failedUserIds().isEmpty());
        assertEquals("kc-sub-7", user.getKeycloakSub());
        verify(keycloakAdminClient).sendPasswordResetEmail("kc-sub-7");
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
