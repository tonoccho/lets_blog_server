package com.letsblog.publishing.service;

import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * publishing-serviceのAdminAuthorizationServiceの単体テスト。{@code requireAdmin}はissue #708、
 * {@code requireProjectMemberOrAdmin}はArticlePreviewControllerの移設に伴い追加した(issue #712)。
 * content-service/ai-service側の同名テストと同じ観点(admin許可/非admin拒否/プロジェクトメンバー
 * 許可/非メンバー拒否/操作者未解決)を、本サービスの依存関係(CurrentActorService経由の
 * identity-service委譲、IdentityBridgeClient経由のプロジェクトメンバー判定)に合わせて検証する。
 *
 * <p>issue #1815で書き直し: CurrentActorService・IdentityClient・IdentityBridgeClientは実物を使い、
 * identity-serviceへのHTTP({@code FakeHttpServer})だけを差し替える。HTTPリクエストはSpring Testの
 * {@code MockHttpServletRequest}。
 */
class AdminAuthorizationServiceTest {

    private static final String ADMIN = "Bearer admin-token";
    private static final String MEMBER = "Bearer member-token";

    private PublishingWorld world;
    private AdminAuthorizationService service;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = new AdminAuthorizationService(world.currentActorService, world.identityBridgeClient);
        world.stubActor(ADMIN, 1L, "admin", "admin@example.com").stubActor(MEMBER, 10L, "user", "m@example.com");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private int memberLookups() {
        return world.identity.requests("GET", "/api/internal/identity/projects/.*").size();
    }

    @Test
    void requireAdmin_adminなら例外を投げない() {
        world.asUser(ADMIN);

        assertDoesNotThrow(() -> service.requireAdmin());
    }

    @Test
    void requireAdmin_admin以外はForbidden() {
        world.asUser(MEMBER);

        assertThrows(ForbiddenException.class, () -> service.requireAdmin());
    }

    @Test
    void requireAdmin_Authorizationが無ければForbidden() {
        world.asUser(null);

        assertThrows(ForbiddenException.class, () -> service.requireAdmin());
        assertTrue(world.identity.requests().isEmpty(), "ヘッダーが無ければidentity-serviceへ問い合わせない");
    }

    @Test
    void requireProjectMemberOrAdmin_adminなら無条件で許可() {
        world.asUser(ADMIN);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
        // adminは所属を問わずバイパスするため、メンバー判定の問い合わせ自体を行わない。
        assertTrue(memberLookups() == 0);
    }

    @Test
    void requireProjectMemberOrAdmin_プロジェクトメンバーなら許可() {
        world.asUser(MEMBER).stubProjectMember(1L, 10L, true);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_メンバーでなければ例外() {
        world.asUser(MEMBER).stubProjectMember(1L, 10L, false);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_操作者を解決できなければ例外() {
        world.asUser("Bearer unknown-token");

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
        assertTrue(memberLookups() == 0);
    }

    @Test
    void requireProjectMemberOrAdminForSite_プロジェクトに紐付くサイトは通常のメンバー判定に委ねる() {
        world.asUser(MEMBER).stubProjectMember(1L, 10L, true);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdminForSite(1L));
    }

    @Test
    void requireProjectMemberOrAdminForSite_未紐付けサイトはadminのみ許可() {
        // issue #759: どの環境にも紐付いていないサイトはprojectIdがnullになりうる。
        // 判定に使えるメンバーシップが無いので、adminだけを通す(issue #830)。
        world.asUser(ADMIN);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdminForSite(null));
        // projectIdがnullの状態でメンバー判定へ進むと、nullのprojectIdを送ってしまう。
        assertTrue(memberLookups() == 0);
    }

    @Test
    void requireProjectMemberOrAdminForSite_未紐付けサイトへの非adminはForbidden() {
        world.asUser(MEMBER);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdminForSite(null));
        assertTrue(memberLookups() == 0);
    }
}
