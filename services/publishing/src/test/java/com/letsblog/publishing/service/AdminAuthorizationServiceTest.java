package com.letsblog.publishing.service;

import com.letsblog.publishing.client.IdentityBridgeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * publishing-serviceのAdminAuthorizationServiceの単体テスト。{@code requireAdmin}はissue #708、
 * {@code requireProjectMemberOrAdmin}はArticlePreviewControllerの移設に伴い追加した(issue #712)。
 * content-service/ai-service側の同名テストと同じ観点(admin許可/非admin拒否/プロジェクトメンバー
 * 許可/非メンバー拒否/操作者未解決)を、本サービスの依存関係(CurrentActorService経由の
 * identity-service委譲、IdentityBridgeClient経由のプロジェクトメンバー判定)に合わせて検証する。
 */
@ExtendWith(MockitoExtension.class)
class AdminAuthorizationServiceTest {

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
        // adminは所属を問わずバイパスするため、legacy-apiへの問い合わせ自体を行わない。
        verifyNoInteractions(identityBridgeClient);
    }

    @Test
    void requireProjectMemberOrAdmin_プロジェクトメンバーなら許可() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(identityBridgeClient.isProjectMember(1L, 10L)).thenReturn(true);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_メンバーでなければ例外() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(identityBridgeClient.isProjectMember(1L, 10L)).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_操作者を解決できなければ例外() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
        verifyNoInteractions(identityBridgeClient);
    }

    @Test
    void requireProjectMemberOrAdminForSite_プロジェクトに紐付くサイトは通常のメンバー判定に委ねる() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(identityBridgeClient.isProjectMember(1L, 10L)).thenReturn(true);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdminForSite(1L));
    }

    @Test
    void requireProjectMemberOrAdminForSite_未紐付けサイトはadminのみ許可() {
        // issue #759: どの環境にも紐付いていないサイトはprojectIdがnullになりうる。
        // 判定に使えるメンバーシップが無いので、adminだけを通す(issue #830)。
        when(currentActorService.isAdmin()).thenReturn(true);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdminForSite(null));
        // projectIdがnullの状態でメンバー判定へ進むと、legacy-apiへnullのprojectIdを送ってしまう。
        verifyNoInteractions(identityBridgeClient);
    }

    @Test
    void requireProjectMemberOrAdminForSite_未紐付けサイトへの非adminはForbidden() {
        when(currentActorService.isAdmin()).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdminForSite(null));
        verifyNoInteractions(identityBridgeClient);
    }
}
