package com.letsblog.ai.service;

import com.letsblog.ai.client.LegacyApiBridgeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ai-serviceのAdminAuthorizationService(legacy-apiから複製されたrequireAdmin/
 * requireProjectMemberOrAdmin、issue #574)の単体テスト(issue #644)。legacy-apiの
 * {@code com.letsblog.api.service.AdminAuthorizationServiceTest}と同じ観点(admin許可/非admin拒否/
 * プロジェクトメンバー許可/非メンバー拒否)を、本サービスの依存関係(CurrentActorService経由の
 * identity-service委譲、LegacyApiBridgeClient経由のプロジェクトメンバー判定)に合わせて検証する。
 */
@ExtendWith(MockitoExtension.class)
class AdminAuthorizationServiceTest {

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private LegacyApiBridgeClient legacyApiBridgeClient;

    private AdminAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new AdminAuthorizationService(currentActorService, legacyApiBridgeClient);
    }

    @Test
    void requireAdmin_adminなら例外を投げない() {
        when(currentActorService.isAdmin()).thenReturn(true);

        assertDoesNotThrow(() -> service.requireAdmin());
    }

    @Test
    void requireAdmin_admin以外はForbidden() {
        when(currentActorService.isAdmin()).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.requireAdmin());
    }

    @Test
    void requireProjectMemberOrAdmin_adminなら無条件で許可() {
        lenient().when(currentActorService.isAdmin()).thenReturn(true);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_プロジェクトメンバーなら許可() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer xxx");
        when(legacyApiBridgeClient.isProjectMember(1L, 10L, "Bearer xxx")).thenReturn(true);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_メンバーでなければ例外() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer xxx");
        when(legacyApiBridgeClient.isProjectMember(1L, 10L, "Bearer xxx")).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_操作者を解決できなければ例外() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }
}
