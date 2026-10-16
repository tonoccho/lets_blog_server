package com.letsblog.identity.service;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.UserUpdateRequest;
import com.letsblog.identity.repository.UserRepository;
import com.letsblog.identity.testsupport.FakeKeycloak;
import com.letsblog.identity.testsupport.IdentityWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1162: 最後の有効なadminの保護の分岐単位の検証。実DBでの原子性(同時実行)は
 * {@code LastAdminGuardIntegrationTest}が担う。
 *
 * <p>{@code UserService}・{@code KeycloakAdminClient}・{@code AuditLogService}・
 * {@code DomainEventPublisher}は実物。DBのリポジトリ(Mockito)、KeycloakのHTTP({@link FakeKeycloak})だけが
 * 外部との通信として差し替わる。「ロックを先に取ってからKeycloakを触る」順序は、ロック取得の瞬間に
 * Keycloakへ何も送られていないことで確かめる。
 */
class UserServiceLastAdminGuardTest {

    private IdentityWorld world;
    private UserRepository userRepository;
    private FakeKeycloak keycloak;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
        userRepository = world.userRepository;
        keycloak = world.keycloak;
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

        assertTrue(keycloak.ops().isEmpty(), keycloak.ops().toString());
        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void deactivate_唯一の有効なadminなら拒否しKeycloakにも触れない() {
        User admin = user(1L, "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.lockEnabledAdmins()).thenReturn(List.of(admin));

        assertThrows(ForbiddenException.class, () -> service().deactivate(1L));

        assertTrue(keycloak.ops().isEmpty(), keycloak.ops().toString());
        verify(userRepository, never()).save(any());
        assertTrue(world.domainEvents().isEmpty());
    }

    @Test
    void delete_有効なadminが2人いれば通り_ロックを先に取ってから削除する() {
        User target = user(1L, "admin");
        User other = user(2L, "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        AtomicReference<List<String>> opsAtLock = new AtomicReference<>();
        when(userRepository.lockEnabledAdmins()).thenAnswer(invocation -> {
            opsAtLock.set(keycloak.ops());
            return List.of(target, other);
        });
        AtomicReference<List<String>> opsAtDeleteById = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            opsAtDeleteById.set(keycloak.ops());
            return null;
        }).when(userRepository).deleteById(1L);

        service().delete(1L);

        // ロック → Keycloakの削除 → ローカルの削除、の順。
        assertEquals(List.of(), opsAtLock.get());
        assertEquals(List.of("delete:kc-1"), opsAtDeleteById.get());
        verify(userRepository).deleteById(1L);
    }

    @Test
    void deactivate_有効なadminが2人いれば通る() {
        User target = user(1L, "admin");
        User other = user(2L, "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.lockEnabledAdmins()).thenReturn(List.of(target, other));

        service().deactivate(1L);

        assertEquals(List.of("setEnabled:kc-1:false"), keycloak.ops());
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

        assertTrue(e.getMessage().contains("最後の管理者"));
        assertTrue(keycloak.ops().isEmpty(), keycloak.ops().toString());
        verify(userRepository, never()).saveAndFlush(any());
        assertTrue(world.auditMessages().isEmpty());
    }

    @Test
    void update_有効なadminが2人いれば降格でき_ロックを先に取ってからKeycloakを触る() {
        User target = user(1L, "admin");
        User other = user(2L, "admin");
        keycloak.alreadyAssigned("kc-1", "admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        AtomicReference<List<String>> opsAtLock = new AtomicReference<>();
        when(userRepository.lockEnabledAdmins()).thenAnswer(invocation -> {
            opsAtLock.set(keycloak.ops());
            return List.of(target, other);
        });
        AtomicReference<List<String>> opsAtSave = new AtomicReference<>();
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            opsAtSave.set(keycloak.ops());
            return invocation.getArgument(0);
        });

        service().update(1L, new UserUpdateRequest("user", null));

        // ロック → Keycloakのrealmロール剥奪 → ローカルの保存、の順。
        assertEquals(List.of(), opsAtLock.get());
        assertEquals(List.of("revoke:kc-1:admin"), opsAtSave.get());
        verify(userRepository).saveAndFlush(target);
    }

    @Test
    void update_adminのままのrole指定_パスワードのみ_非admin昇格ではロックしない() {
        User admin = user(1L, "admin");
        User plain = user(2L, "user");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findById(2L)).thenReturn(Optional.of(plain));

        service().update(1L, new UserUpdateRequest("admin", null));
        service().update(1L, new UserUpdateRequest(null, null));
        service().update(2L, new UserUpdateRequest("user", null));

        verify(userRepository, never()).lockEnabledAdmins();
    }
}
