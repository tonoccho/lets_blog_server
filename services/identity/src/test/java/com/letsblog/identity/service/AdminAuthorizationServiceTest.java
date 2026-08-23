package com.letsblog.identity.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthorizationServiceTest {

    @Mock
    private CurrentActorService currentActorService;

    private AdminAuthorizationService service() {
        return new AdminAuthorizationService(currentActorService);
    }

    @Test
    void requireSelfOrAdmin_admin操作者は誰でも許可() {
        AdminAuthorizationService service = service();
        when(currentActorService.isAdmin()).thenReturn(true);

        assertDoesNotThrow(() -> service.requireSelfOrAdmin(99L));
    }

    @Test
    void requireSelfOrAdmin_本人は許可() {
        AdminAuthorizationService service = service();
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(1L);

        assertDoesNotThrow(() -> service.requireSelfOrAdmin(1L));
    }

    @Test
    void requireSelfOrAdmin_他人は拒否() {
        AdminAuthorizationService service = service();
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(1L);

        assertThrows(ForbiddenException.class, () -> service.requireSelfOrAdmin(2L));
    }
}
