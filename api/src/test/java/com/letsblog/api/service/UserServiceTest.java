package com.letsblog.api.service;

import com.letsblog.api.domain.TwoFactorSecret;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.LoginResponse;
import com.letsblog.api.dto.UserProfileResponse;
import com.letsblog.api.dto.UserProfileUpdateRequest;
import com.letsblog.api.dto.UserResponse;
import com.letsblog.api.repository.RoleRepository;
import com.letsblog.api.repository.TwoFactorSecretRepository;
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
    private TwoFactorSecretRepository twoFactorSecretRepository;

    @Mock
    private RoleRepository roleRepository;

    private UserService service;

    private UserService service() {
        return new UserService(userRepository, twoFactorSecretRepository, roleRepository);
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
    void login_2FA未設定ユーザーはtwoFactorRequiredがfalse() {
        service = service();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(buildUser()));
        when(twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(1L)).thenReturn(Optional.empty());

        LoginResponse response = service.login("user@example.com", "password123");

        assertFalse(response.twoFactorRequired());
        assertEquals("user@example.com", response.user().email());
    }

    @Test
    void login_2FA有効ユーザーはtwoFactorRequiredがtrue() {
        service = service();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(buildUser()));
        when(twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(1L))
                .thenReturn(Optional.of(new TwoFactorSecret()));

        LoginResponse response = service.login("user@example.com", "password123");

        assertTrue(response.twoFactorRequired());
    }

    @Test
    void login_パスワード不一致は例外() {
        service = service();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(buildUser()));

        assertThrows(InvalidCredentialsException.class,
                () -> service.login("user@example.com", "wrong-password"));
    }

    @Test
    void updateUserProfile_プロフィール更新完了() {
        service = service();
        User user = buildUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileUpdateRequest request = new UserProfileUpdateRequest(
                "太郎", "山田", "山田太郎", "taro",
                "https://example.com", "自己紹介", "ja_JP",
                "https://gravatar.com/avatar/xxx", "開発部", "エンジニア");

        UserProfileResponse response = service.updateUserProfile(1L, request);

        assertEquals("太郎", response.firstName());
        assertEquals("山田", response.lastName());
        assertEquals("山田太郎", response.displayName());
        assertEquals("開発部", response.department());
        assertEquals("エンジニア", response.position());
        assertEquals("ja_JP", response.locale());
    }

    @Test
    void updateUserProfile_存在しないユーザーは例外() {
        service = service();
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        UserProfileUpdateRequest request = new UserProfileUpdateRequest(
                null, null, null, null, null, null, null, null, null, null);

        assertThrows(UserNotFoundException.class, () -> service.updateUserProfile(99L, request));
    }

    @Test
    void findUserWithProfile_拡張フィールドを含めて返却() {
        service = service();
        User user = buildUser();
        user.setDisplayName("山田太郎");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserProfileResponse response = service.findUserWithProfile(1L);

        assertEquals("山田太郎", response.displayName());
        assertEquals("user@example.com", response.email());
    }
}
