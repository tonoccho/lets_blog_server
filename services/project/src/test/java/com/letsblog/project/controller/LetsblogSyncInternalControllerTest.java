package com.letsblog.project.controller;

import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.LetsblogSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * content-service が、タグ・CSS・プレフィックスの変更をサイトへの同期として依頼する内部API(issue #1558)。
 * issue #1617: 呼び出し元ユーザーの権限(プロジェクトメンバーまたはadmin、グローバルはadminのみ)を検査する。
 */
@ExtendWith(MockitoExtension.class)
class LetsblogSyncInternalControllerTest {

    @Mock
    private LetsblogSyncService letsblogSyncService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private LetsblogSyncInternalController controller() {
        return new LetsblogSyncInternalController(letsblogSyncService, adminAuthorizationService);
    }

    @Test
    void プロジェクトメンバーまたはadminならそのプロジェクトの同期を依頼して202を返す() {
        var response = controller().request(new LetsblogSyncInternalController.SyncRequest(7L));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        InOrder order = inOrder(adminAuthorizationService, letsblogSyncService);
        order.verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
        order.verify(letsblogSyncService).requestProjectSync(7L);
    }

    @Test
    void プロジェクトのメンバーでなければ403で同期は依頼されない() {
        doThrow(new ForbiddenException("forbidden")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThrows(ForbiddenException.class,
                () -> controller().request(new LetsblogSyncInternalController.SyncRequest(7L)));

        verifyNoInteractions(letsblogSyncService);
    }

    @Test
    void プロジェクトIDが無くadminならすべてのプロジェクトの同期を依頼する() {
        var response = controller().request(new LetsblogSyncInternalController.SyncRequest(null));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        InOrder order = inOrder(adminAuthorizationService, letsblogSyncService);
        order.verify(adminAuthorizationService).requireAdmin();
        order.verify(letsblogSyncService).requestAllSync();
    }

    @Test
    void プロジェクトIDが無くadminでなければ403で同期は依頼されない() {
        doThrow(new ForbiddenException("forbidden")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().request(new LetsblogSyncInternalController.SyncRequest(null)));

        verify(adminAuthorizationService).requireAdmin();
        verifyNoInteractions(letsblogSyncService);
    }
}
