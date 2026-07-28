package com.letsblog.api.service;

import com.letsblog.api.domain.User;
import com.letsblog.api.dto.UserResponse;
import com.letsblog.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private UserService service;

    private UserService service() {
        return new UserService(userRepository);
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
}
