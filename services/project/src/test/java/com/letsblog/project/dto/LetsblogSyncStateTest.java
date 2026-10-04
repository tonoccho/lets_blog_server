package com.letsblog.project.dto;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.LetsblogSyncStatus;
import com.letsblog.project.domain.Site;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** サイトの同期状態の表現。サイト一覧で失敗したサイトを見分けるため SiteResponse に含める(issue #1558)。 */
class LetsblogSyncStateTest {

    private Site site() {
        Site site = new Site();
        site.setId(1L);
        site.setName("n");
        site.setSiteKey("k");
        site.setCmsType(CmsType.WORDPRESS);
        site.setBaseUrl("https://example.com");
        return site;
    }

    @Test
    void 同期したことのないサイトの状態はnull() {
        assertNull(LetsblogSyncState.from(site()));
        assertNull(SiteResponse.from(site()).letsblogSync());
    }

    @Test
    void 状態はステータスとエラーとハッシュと日時を持つ() {
        Site site = site();
        site.setLetsblogSyncStatus(LetsblogSyncStatus.FAILED);
        site.setLetsblogSyncError("接続できません");
        site.setLetsblogSyncHash("h1");
        site.setLetsblogSyncedAt(LocalDateTime.of(2026, 10, 4, 1, 2, 3));

        LetsblogSyncState state = LetsblogSyncState.from(site);

        assertEquals(LetsblogSyncStatus.FAILED, state.status());
        assertEquals("接続できません", state.error());
        assertEquals("h1", state.hash());
        assertNotNull(state.syncedAt());
    }

    @Test
    void サイト一覧の応答に同期状態が含まれる() {
        Site site = site();
        site.setLetsblogSyncStatus(LetsblogSyncStatus.FAILED);

        assertEquals(LetsblogSyncStatus.FAILED, SiteResponse.from(site).letsblogSync().status());
    }

    @Test
    void 同期日時がnullでも状態は作れる() {
        Site site = site();
        site.setLetsblogSyncStatus(LetsblogSyncStatus.SKIPPED);

        assertNull(LetsblogSyncState.from(site).syncedAt());
    }
}
