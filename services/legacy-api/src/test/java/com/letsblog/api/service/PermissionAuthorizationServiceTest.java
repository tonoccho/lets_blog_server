package com.letsblog.api.service;

import com.letsblog.api.domain.Permission;
import com.letsblog.api.domain.Role;
import com.letsblog.api.domain.User;
import com.letsblog.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionAuthorizationServiceTest {

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private UserRepository userRepository;

    private PermissionAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new PermissionAuthorizationService(currentActorService, userRepository);
    }

    @Test
    void requirePermission_権限を持つユーザーは例外を投げない() {
        Role role = new Role("ROLE_ADMIN", "管理者");
        role.getPermissions().add(Permission.ROLE_MANAGE);
        User user = new User();
        user.getRoles().add(role);

        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.requirePermission(Permission.ROLE_MANAGE);
    }

    @Test
    void requirePermission_権限を持たないユーザーは例外() {
        User user = new User();
        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThrows(ForbiddenException.class, () -> service.requirePermission(Permission.ROLE_MANAGE));
    }

    @Test
    void requirePermission_未認証は例外() {
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service.requirePermission(Permission.ROLE_MANAGE));
    }
}
