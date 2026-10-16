package com.letsblog.platform.service;

import com.letsblog.platform.client.IdentityBridgeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** platform-serviceのrequireProjectMemberOrAdmin(issue #1808)。ai-serviceの同名メソッドと同じ方針。 */
@ExtendWith(MockitoExtension.class)
class AdminAuthorizationServiceProjectMemberTest {

    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private IdentityBridgeClient identityBridgeClient;

    private AdminAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new AdminAuthorizationService(currentActorService, identityBridgeClient);
    }

    @Test
    void 管理者はメンバー確認なしで通る() {
        when(currentActorService.isAdmin()).thenReturn(true);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(10L));
        verifyNoInteractions(identityBridgeClient);
    }

    @Test
    void 操作者を解決できなければ拒否する() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(10L));
        verifyNoInteractions(identityBridgeClient);
    }

    @Test
    void メンバーは通り_メンバーでなければ拒否する() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(5L);
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
        when(identityBridgeClient.isProjectMember(10L, 5L, "Bearer t")).thenReturn(true);
        when(identityBridgeClient.isProjectMember(11L, 5L, "Bearer t")).thenReturn(false);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(10L));
        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(11L));
    }
}
