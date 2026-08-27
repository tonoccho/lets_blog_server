package com.letsblog.media.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * media-serviceのAdminAuthorizationService(legacy-apiから複製されたrequireAdminのみ、issue #573)の
 * 単体テスト(issue #644)。log-writerの{@code AdminAuthorizationServiceTest}と同じ構造(admin許可/
 * 非admin拒否)。
 *
 * <p>media-serviceのAdminAuthorizationServiceはrequireAdminのみを持ち、
 * requireProjectMemberOrAdmin(プロジェクトメンバー許可/非メンバー拒否)は移設されていない
 * (content/ai/analytics-serviceと異なり、media-serviceの認可対象エンドポイント
 * (ProjectMediaGarbageCollectionController)はadmin限定のためプロジェクトメンバー判定自体が
 * 存在しない)。そのため本クラスはrequireAdminの2ケースのみを検証する。
 */
@ExtendWith(MockitoExtension.class)
class AdminAuthorizationServiceTest {

    @Mock
    private CurrentActorService currentActorService;

    private AdminAuthorizationService service() {
        return new AdminAuthorizationService(currentActorService);
    }

    @Test
    void requireAdmin_adminなら例外を投げない() {
        when(currentActorService.isAdmin()).thenReturn(true);

        assertDoesNotThrow(() -> service().requireAdmin());
    }

    @Test
    void requireAdmin_admin以外はForbidden() {
        when(currentActorService.isAdmin()).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service().requireAdmin());
    }
}
