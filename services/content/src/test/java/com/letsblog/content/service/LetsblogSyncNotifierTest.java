package com.letsblog.content.service;

import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * タグ・CSS・プレフィックスの変更を project-service へ伝えて、サイトへの同期を依頼する(issue #1558)。
 * project-service は content-service から最新の内容を読み直すため、依頼はコミット後に出す。
 *
 * <p>issue #1821: ProjectBridgeClient・CurrentActorServiceは実物で、project-serviceの代わりにHTTPフェイクを向ける。
 */
class LetsblogSyncNotifierTest {

    private static final String SYNC_PATH = "/api/internal/project/letsblog-sync";

    private ContentWorld world;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        world.close();
    }

    private LetsblogSyncNotifier notifier() {
        return world.letsblogSyncNotifier;
    }

    @Test
    void プロジェクトの変更はそのプロジェクトのIDと呼び出し元のトークンで依頼する() {
        notifier().notifyProjectChanged(7L);

        var requests = world.http.requests("POST", SYNC_PATH);
        assertEquals(1, requests.size());
        assertEquals("Bearer token-1", requests.get(0).header("Authorization"));
        assertEquals(7, world.bodyOf(requests.get(0)).get("projectId").asInt());
    }

    @Test
    void グローバルの変更はプロジェクトIDなしで依頼する() {
        notifier().notifyGlobalChanged();

        var requests = world.http.requests("POST", SYNC_PATH);
        assertEquals(1, requests.size());
        assertTrue(world.bodyOf(requests.get(0)).get("projectId").isNull());
    }

    @Test
    void 依頼に失敗しても例外を投げない() {
        world.http.status("POST", SYNC_PATH, 503, "{\"error\":\"down\"}");

        notifier().notifyProjectChanged(7L);

        assertEquals(1, world.http.requests("POST", SYNC_PATH).size());
    }

    @Test
    void トランザクション中はコミットされるまで依頼しない() {
        TransactionSynchronizationManager.initSynchronization();

        notifier().notifyProjectChanged(7L);

        assertTrue(world.http.requests("POST", SYNC_PATH).isEmpty());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        var requests = world.http.requests("POST", SYNC_PATH);
        assertEquals(1, requests.size());
        assertEquals("Bearer token-1", requests.get(0).header("Authorization"));
        assertEquals(7, world.bodyOf(requests.get(0)).get("projectId").asInt());
    }

    @Test
    void ロールバックされたら依頼しない() {
        TransactionSynchronizationManager.initSynchronization();

        notifier().notifyGlobalChanged();

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertTrue(world.http.requests("POST", SYNC_PATH).isEmpty());
    }
}
