package com.letsblog.identity.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.UserUpdateRequest;
import com.letsblog.identity.keycloak.KeycloakAdminClient;
import com.letsblog.identity.messaging.DomainEventPublisher;
import com.letsblog.identity.repository.RoleRepository;
import com.letsblog.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1162: 最後の有効なadminの保護の分岐単位の検証(モック)。実DBでの原子性(同時実行)は
 * {@code LastAdminGuardIntegrationTest}が担う。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceLastAdminGuardTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private KeycloakAdminClient keycloakAdminClient;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private UserMigrationPersister userMigrationPersister;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private UserService service() {
        return new UserService(
                userRepository, roleRepository, credentialCipher, keycloakAdminClient, domainEventPublisher,
                auditLogService, userMigrationPersister);
    }

    private User user(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setEmail("u" + id + "@example.test");
        user.setRole(role);
        user.setEnabled(true);
        user.setKeycloakSub("kc-" + id);
        return user;
    }

    @Test
    void delete_唯一の有効なadminなら拒否しKeycloakにも触れない() {
        User admin = user(1L, "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.lockEnabledAdmins()).thenReturn(List.of(admin));

        assertThrows(ForbiddenException.class, () -> service().delete(1L));

        verify(keycloakAdminClient, never()).deleteUser(any());
        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void deactivate_唯一の有効なadminなら拒否しKeycloakにも触れない() {
        User admin = user(1L, "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.lockEnabledAdmins()).thenReturn(List.of(admin));

        assertThrows(ForbiddenException.class, () -> service().deactivate(1L));

        verify(keycloakAdminClient, never()).setEnabled(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(userRepository, never()).save(any());
        verify(domainEventPublisher, never()).publishUserDeactivated(any(), any());
    }

    @Test
    void delete_有効なadminが2人いれば通り_ロックを先に取ってから削除する() {
        User target = user(1L, "admin");
        User other = user(2L, "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.lockEnabledAdmins()).thenReturn(List.of(target, other));

        service().delete(1L);

        InOrder order = inOrder(userRepository, keycloakAdminClient);
        order.verify(userRepository).lockEnabledAdmins();
        order.verify(keycloakAdminClient).deleteUser("kc-1");
        order.verify(userRepository).deleteById(1L);
    }

    @Test
    void deactivate_有効なadminが2人いれば通る() {
        User target = user(1L, "admin");
        User other = user(2L, "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.lockEnabledAdmins()).thenReturn(List.of(target, other));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().deactivate(1L);

        verify(keycloakAdminClient).setEnabled("kc-1", false);
    }

    @Test
    void 対象が既に有効なadminでなければ_他に誰もいなくても通る() {
        // 読み込み後に別トランザクションが対象を無効化した場合。ロック済みの集合に対象は含まれない。
        User target = user(1L, "admin");
        User other = user(2L, "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.lockEnabledAdmins()).thenReturn(List.of(other));

        service().delete(1L);

        verify(userRepository).deleteById(1L);
    }

    @Test
    void admin以外の対象ではロックも数え上げもしない() {
        User target = user(1L, "user");
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));

        service().delete(1L);

        verify(userRepository, never()).lockEnabledAdmins();
        verify(userRepository).deleteById(1L);
    }

    // ---------------------------------------------- issue #1427: PATCHによる降格

    @Test
    void update_唯一の有効なadminの降格は拒否しKeycloakにも保存にも触れない() {
        User admin = user(1L, "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.lockEnabledAdmins()).thenReturn(List.of(admin));

        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> service().update(1L, new UserUpdateRequest("user", null)));

        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("最後の管理者"));
        verify(keycloakAdminClient, never()).revokeRealmRole(any(), any());
        verify(userRepository, never()).saveAndFlush(any());
        verify(auditLogService, never()).logUserRoleUpdated(any(), any(), any());
    }

    @Test
    void update_有効なadminが2人いれば降格でき_ロックを先に取ってからKeycloakを触る() {
        User target = user(1L, "admin");
        User other = user(2L, "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.lockEnabledAdmins()).thenReturn(List.of(target, other));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service().update(1L, new UserUpdateRequest("user", null));

        InOrder order = inOrder(userRepository, keycloakAdminClient);
        order.verify(userRepository).lockEnabledAdmins();
        order.verify(keycloakAdminClient).revokeRealmRole(any(), any());
        order.verify(userRepository).saveAndFlush(target);
    }

    @Test
    void update_adminのままのrole指定_パスワードのみ_非admin昇格ではロックしない() {
        User admin = user(1L, "admin");
        User plain = user(2L, "user");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findById(2L)).thenReturn(Optional.of(plain));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service().update(1L, new UserUpdateRequest("admin", null));
        service().update(1L, new UserUpdateRequest(null, null));
        service().update(2L, new UserUpdateRequest("user", null));

        verify(userRepository, never()).lockEnabledAdmins();
    }
}
