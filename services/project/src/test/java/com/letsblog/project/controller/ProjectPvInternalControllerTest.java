package com.letsblog.project.controller;

import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.PvRuleService;
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
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * analytics-service が、GA のプロパティを選び終えた(連携が完了した)ことを知らせる内部API(issue #1578)。
 * 呼び出し元ユーザーがプロジェクトのメンバーかadminであることを検査してから、本番サイトへ GA の認証情報とルールを送る。
 */
@ExtendWith(MockitoExtension.class)
class ProjectPvInternalControllerTest {

    @Mock
    private PvRuleService pvRuleService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    @Test
    void 権限を確かめてから送信を依頼し204を返す() {
        var response = new ProjectPvInternalController(pvRuleService, adminAuthorizationService).googleAnalyticsConnected(7L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        InOrder order = inOrder(adminAuthorizationService, pvRuleService);
        order.verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
        order.verify(pvRuleService).onGoogleAnalyticsConnected(7L);
    }

    @Test
    void メンバーでもadminでもなければ拒否し送信しない() {
        doThrow(new ForbiddenException("forbidden")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThrows(ForbiddenException.class,
                () -> new ProjectPvInternalController(pvRuleService, adminAuthorizationService).googleAnalyticsConnected(7L));

        verifyNoInteractions(pvRuleService);
    }
}
