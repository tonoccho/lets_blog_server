package com.letsblog.identity.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ---------------------------------------------- requireAdminAndNotSelf(#796で追加、#798で拡張)

    @Test
    void requireAdminAndNotSelf_adminが他人を対象にする場合は許可() {
        AdminAuthorizationService service = service();
        when(currentActorService.isAdmin()).thenReturn(true);
        when(currentActorService.getCurrentActorId()).thenReturn(1L);

        assertDoesNotThrow(() -> service.requireAdminAndNotSelf(2L, "自分自身は対象にできません"));
    }

    @Test
    void requireAdminAndNotSelf_adminでも自分自身は拒否() {
        AdminAuthorizationService service = service();
        when(currentActorService.isAdmin()).thenReturn(true);
        when(currentActorService.getCurrentActorId()).thenReturn(1L);

        ForbiddenException thrown = assertThrows(
                ForbiddenException.class,
                () -> service.requireAdminAndNotSelf(1L, "自分自身のアカウントは無効化できません"));
        // 呼び出し元が渡したメッセージがそのまま使われる(削除と無効化で文言を変えるため)。
        assertEquals("自分自身のアカウントは無効化できません", thrown.getMessage());
    }

    @Test
    void requireAdminAndNotSelf_非adminはadmin判定で先に拒否() {
        AdminAuthorizationService service = service();
        when(currentActorService.isAdmin()).thenReturn(false);

        ForbiddenException thrown = assertThrows(
                ForbiddenException.class,
                () -> service.requireAdminAndNotSelf(2L, "自分自身のアカウントは削除できません"));
        assertEquals("この操作にはadmin権限が必要です", thrown.getMessage());
    }

    /**
     * 操作者を解決できない場合({@code getCurrentActorId()}がnull)は自己判定をスキップする。
     * この経路に到達するのは「admin判定は通ったが操作者IDが無い」という状態だが、
     * {@code isAdmin()}自体が操作者の解決結果を見ているため実際には起こらない。
     * 将来admin判定の実装が変わったときに、null が「自分自身ではない」と扱われる
     * (＝fail-openにならない)ことを固定しておく。
     */
    @Test
    void requireAdminAndNotSelf_操作者IDが解決できない場合は自己判定をスキップ() {
        AdminAuthorizationService service = service();
        when(currentActorService.isAdmin()).thenReturn(true);
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertDoesNotThrow(() -> service.requireAdminAndNotSelf(1L, "自分自身は対象にできません"));
    }
}
