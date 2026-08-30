package com.letsblog.api.service;

import com.letsblog.api.keycloak.KeycloakAdminClient;
import com.letsblog.api.keycloak.KeycloakAdminException;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.UserResponse;
import com.letsblog.api.repository.RoleRepository;
import com.letsblog.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private UserService service;

    private UserService service() {
        return new UserService(userRepository, roleRepository, credentialCipher, keycloakAdminClient);
    }

    @Test
    void hasAnyUser_ユーザーが存在すればtrue() {
        service = service();
        when(userRepository.count()).thenReturn(1L);

        assertTrue(service.hasAnyUser());
    }

    @Test
    void hasAnyUser_ユーザーが存在しなければfalse() {
        service = service();
        when(userRepository.count()).thenReturn(0L);

        assertFalse(service.hasAnyUser());
    }

    @Test
    void setupInitialAdmin_usersが空ならKeycloakとローカルの両方に管理者を作成する() {
        service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(keycloakAdminClient.createUser("admin@example.com")).thenReturn("kc-sub-1");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1L);
            return u;
        });

        UserResponse response = service.setupInitialAdmin("admin@example.com", "password123");

        assertEquals("admin", response.role());
        verify(keycloakAdminClient).createUser("admin@example.com");
        verify(keycloakAdminClient).setPassword("kc-sub-1", "password123");
        verify(keycloakAdminClient, never()).deleteUser(any());
    }

    @Test
    void setupInitialAdmin_usersが既に存在すれば例外でKeycloakは呼ばれない() {
        service = service();
        when(userRepository.count()).thenReturn(1L);

        assertThrows(IllegalArgumentException.class,
                () -> service.setupInitialAdmin("admin@example.com", "password123"));
        verify(keycloakAdminClient, never()).createUser(any());
    }

    @Test
    void setupInitialAdmin_Keycloakに既にユーザーが存在すれば例外になりローカルには作成されない() {
        service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(keycloakAdminClient.createUser("admin@example.com"))
                .thenThrow(new KeycloakAdminException("Keycloak側に同一のユーザーが既に存在します"));

        assertThrows(KeycloakAdminException.class,
                () -> service.setupInitialAdmin("admin@example.com", "password123"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void setupInitialAdmin_パスワード設定失敗時はKeycloakユーザーを補償削除する() {
        service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(keycloakAdminClient.createUser("admin@example.com")).thenReturn("kc-sub-2");
        org.mockito.Mockito.doThrow(new KeycloakAdminException("Keycloak APIの呼び出しに失敗しました"))
                .when(keycloakAdminClient).setPassword("kc-sub-2", "password123");

        assertThrows(KeycloakAdminException.class,
                () -> service.setupInitialAdmin("admin@example.com", "password123"));

        verify(keycloakAdminClient).deleteUser("kc-sub-2");
        verify(userRepository, never()).save(any());
    }

    @Test
    void setupInitialAdmin_ローカルに既に同一メールが存在すれば例外でKeycloakは呼ばれない() {
        service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class,
                () -> service.setupInitialAdmin("admin@example.com", "password123"));
        verifyNoInteractions(keycloakAdminClient);
    }

    @Test
    void setupInitialAdmin_ローカル保存失敗時はKeycloakユーザーを補償削除する() {
        service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(keycloakAdminClient.createUser("admin@example.com")).thenReturn("kc-sub-3");
        when(userRepository.save(any(User.class))).thenThrow(new RuntimeException("DB接続エラー"));

        assertThrows(RuntimeException.class,
                () -> service.setupInitialAdmin("admin@example.com", "password123"));

        verify(keycloakAdminClient).setPassword("kc-sub-3", "password123");
        verify(keycloakAdminClient).deleteUser("kc-sub-3");
    }

    private User buildUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@example.com");
        user.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password123"));
        user.setRole("user");
        return user;
    }

    @Test
    void getDecryptedGithubToken_復号された値が元の値と一致する() {
        service = service();
        User user = buildUser();
        user.setGithubTokenEncrypted(credentialCipher.encrypt("ghp_dummy"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertEquals("ghp_dummy", service.getDecryptedGithubToken(1L));
    }

    @Test
    void getDecryptedGithubToken_未設定なら例外() {
        service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThrows(IllegalStateException.class, () -> service.getDecryptedGithubToken(1L));
    }
}
