package com.letsblog.identity.service;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.domain.Role;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.RoleAssignmentResult;
import com.letsblog.identity.repository.RoleRepository;
import com.letsblog.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ロールの割り当て。DBのリポジトリ(Spring Data)だけをMockitoで差し替える。 */
class RoleServiceTest {

    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private RoleService service() {
        return new RoleService(roleRepository, userRepository);
    }

    @Test
    void getAllRoles_全ロールを返す() {
        RoleService service = service();
        List<Role> roles = List.of(new Role("ROLE_ADMIN", "管理者"), new Role("ROLE_VIEWER", "閲覧者"));
        when(roleRepository.findAll()).thenReturn(roles);

        assertEquals(2, service.getAllRoles().size());
    }

    @Test
    void isPrivilegedRole_ROLE_MANAGEを持つロールは特権() {
        Role role = new Role("ROLE_ADMIN", "管理者");
        role.getPermissions().add(Permission.ROLE_MANAGE);
        when(roleRepository.findByRoleName("ROLE_ADMIN")).thenReturn(Optional.of(role));

        assertTrue(service().isPrivilegedRole("ROLE_ADMIN"));
    }

    @Test
    void isPrivilegedRole_USER_ROLE_MANAGEだけを持つロールも特権() {
        Role role = new Role("ROLE_HR", "人事");
        role.getPermissions().add(Permission.USER_ROLE_MANAGE);
        when(roleRepository.findByRoleName("ROLE_HR")).thenReturn(Optional.of(role));

        assertTrue(service().isPrivilegedRole("ROLE_HR"));
    }

    @Test
    void isPrivilegedRole_どちらも持たないロールは特権でない() {
        Role role = new Role("ROLE_VIEWER", "閲覧者");
        role.getPermissions().add(Permission.POST_READ);
        when(roleRepository.findByRoleName("ROLE_VIEWER")).thenReturn(Optional.of(role));

        assertFalse(service().isPrivilegedRole("ROLE_VIEWER"));
    }

    @Test
    void isPrivilegedRole_存在しないロールは特権でない() {
        when(roleRepository.findByRoleName("NOPE")).thenReturn(Optional.empty());

        assertFalse(service().isPrivilegedRole("NOPE"));
    }

    @Test
    void assignRoleToUser_ユーザーにロールを追加する() {
        RoleService service = service();
        User user = new User();
        user.setId(1L);
        Role role = new Role("ROLE_EDITOR", "編集者");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roleRepository.findByRoleName("ROLE_EDITOR")).thenReturn(Optional.of(role));

        RoleAssignmentResult result = service.assignRoleToUser(1L, "ROLE_EDITOR");

        assertTrue(user.getRoles().contains(role));
        assertEquals(1L, result.userId());
        assertEquals("ROLE_EDITOR", result.roleName());
        verify(userRepository).save(user);
    }

    @Test
    void assignRoleToUser_ユーザーが存在しなければ例外() {
        RoleService service = service();
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service.assignRoleToUser(1L, "ROLE_EDITOR"));
    }

    @Test
    void assignRoleToUser_ロールが存在しなければ例外() {
        RoleService service = service();
        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roleRepository.findByRoleName("UNKNOWN")).thenReturn(Optional.empty());

        assertThrows(RoleNotFoundException.class, () -> service.assignRoleToUser(1L, "UNKNOWN"));
    }

    @Test
    void removeRoleFromUser_ユーザーからロールを削除する() {
        RoleService service = service();
        User user = new User();
        user.setId(1L);
        Role role = new Role("ROLE_EDITOR", "編集者");
        user.getRoles().add(role);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roleRepository.findByRoleName("ROLE_EDITOR")).thenReturn(Optional.of(role));

        service.removeRoleFromUser(1L, "ROLE_EDITOR");

        assertFalse(user.getRoles().contains(role));
        verify(userRepository).save(user);
    }

    @Test
    void removeRoleFromUser_ユーザーが存在しなければ例外() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service().removeRoleFromUser(9L, "ROLE_EDITOR"));
    }

    @Test
    void removeRoleFromUser_ロールが存在しなければ例外() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roleRepository.findByRoleName("UNKNOWN")).thenReturn(Optional.empty());

        assertThrows(RoleNotFoundException.class, () -> service().removeRoleFromUser(1L, "UNKNOWN"));
    }
}
