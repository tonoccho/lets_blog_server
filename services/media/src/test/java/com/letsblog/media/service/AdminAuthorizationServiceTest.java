package com.letsblog.media.service;

import com.letsblog.media.client.LegacyApiBridgeClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * media-serviceのAdminAuthorizationService(legacy-apiから複製されたrequireAdminのみ、issue #573)の
 * 単体テスト(issue #644)。log-writerの{@code AdminAuthorizationServiceTest}と同じ構造(admin許可/
 * 非admin拒否)。
 *
 * <p>issue #830 で requireProjectMemberOrAdmin を追加した。それまでは requireAdmin のみで、
 * 「media-serviceの認可対象エンドポイントはadmin限定なのでプロジェクトメンバー判定は要らない」と
 * 記録されていたが、実際にはダイアグラム・生成画像の12エンドポイントが<b>認可チェックを一切
 * 持っていなかった</b>(admin限定だったのはProjectMediaGarbageCollectionControllerだけ)。
 */
@ExtendWith(MockitoExtension.class)
class AdminAuthorizationServiceTest {

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private LegacyApiBridgeClient legacyApiBridgeClient;

    private AdminAuthorizationService service() {
        return new AdminAuthorizationService(currentActorService, legacyApiBridgeClient);
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

    // ---- issue #830 で追加したプロジェクトメンバー判定 ----

    @Test
    void requireProjectMemberOrAdmin_adminなら無条件で許可() {
        when(currentActorService.isAdmin()).thenReturn(true);

        assertDoesNotThrow(() -> service().requireProjectMemberOrAdmin(1L));
        // adminは所属を問わずバイパスするため、legacy-apiへの問い合わせ自体を行わない。
        verifyNoInteractions(legacyApiBridgeClient);
    }

    @Test
    void requireProjectMemberOrAdmin_プロジェクトメンバーなら許可() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
        when(legacyApiBridgeClient.isProjectMember(1L, 10L, "Bearer t")).thenReturn(true);

        assertDoesNotThrow(() -> service().requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_メンバーでなければ例外() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
        when(legacyApiBridgeClient.isProjectMember(1L, 10L, "Bearer t")).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service().requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_操作者を解決できなければ例外() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service().requireProjectMemberOrAdmin(1L));
        verifyNoInteractions(legacyApiBridgeClient);
    }

    @Test
    void requireProjectMemberOrAdminForResource_プロジェクト未紐付けのリソースはadminのみ許可() {
        // projectIdがnullのリソース(プロジェクトに紐付けずに作られたダイアグラム・生成画像)は
        // 判定に使えるメンバーシップが無いため、admin だけを通す。
        when(currentActorService.isAdmin()).thenReturn(true);

        assertDoesNotThrow(() -> service().requireProjectMemberOrAdminForResource(null));
        verifyNoInteractions(legacyApiBridgeClient);
    }

    @Test
    void requireProjectMemberOrAdminForResource_未紐付けリソースへの非adminはForbidden() {
        when(currentActorService.isAdmin()).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service().requireProjectMemberOrAdminForResource(null));
        verifyNoInteractions(legacyApiBridgeClient);
    }
}
