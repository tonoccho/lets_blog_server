package com.letsblog.api.service;

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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private UserService service;

    private UserService service() {
        return new UserService(userRepository, roleRepository, credentialCipher);
    }

    @Test
    void signup_roleはuser固定で作成される() {
        service = service();
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1L);
            return u;
        });

        UserResponse response = service.signup("new@example.com", "password123");

        assertEquals("user", response.role());
        assertEquals("new@example.com", response.email());
    }

    @Test
    void signup_ROLE_VIEWERが自動付与される() {
        service = service();
        com.letsblog.api.domain.Role viewerRole = new com.letsblog.api.domain.Role("ROLE_VIEWER", "閲覧者");
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(roleRepository.findByRoleName("ROLE_VIEWER")).thenReturn(Optional.of(viewerRole));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1L);
            return u;
        });

        UserResponse response = service.signup("new@example.com", "password123");

        assertTrue(response.roleNames().contains("ROLE_VIEWER"));
    }

    @Test
    void signup_既存メールは例外() {
        service = service();
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class,
                () -> service.signup("dup@example.com", "password123"));
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
    void setupInitialAdmin_usersが空なら管理者を作成する() {
        service = service();
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1L);
            return u;
        });

        UserResponse response = service.setupInitialAdmin("admin@example.com", "password123");

        assertEquals("admin", response.role());
    }

    @Test
    void setupInitialAdmin_usersが既に存在すれば例外() {
        service = service();
        when(userRepository.count()).thenReturn(1L);

        assertThrows(IllegalArgumentException.class,
                () -> service.setupInitialAdmin("admin@example.com", "password123"));
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

    @Test
    void resetPassword_新しいパスワードでハッシュが更新される() {
        service = service();
        User user = buildUser();
        String oldHash = user.getPasswordHash();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.resetPassword("user@example.com", "newpassword123");

        assertEquals("user@example.com", response.email());
        assertFalse(user.getPasswordHash().equals(oldHash));
        assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches("newpassword123", user.getPasswordHash()));
    }

    @Test
    void resetPassword_存在しないユーザーは例外() {
        service = service();
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> service.resetPassword("missing@example.com", "newpassword123"));
    }

    @Test
    void resetPassword_パスワードが短すぎる場合は例外() {
        service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword("user@example.com", "short"));
    }
}
