package com.letsblog.content.service;

import com.letsblog.content.client.ProjectBridgeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * タグ・CSS・プレフィックスの変更を project-service へ伝えて、サイトへの同期を依頼する(issue #1558)。
 * project-service は content-service から最新の内容を読み直すため、依頼はコミット後に出す。
 */
@ExtendWith(MockitoExtension.class)
class LetsblogSyncNotifierTest {

    @Mock
    private ProjectBridgeClient projectBridgeClient;
    @Mock
    private CurrentActorService currentActorService;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private LetsblogSyncNotifier notifier() {
        return new LetsblogSyncNotifier(projectBridgeClient, currentActorService);
    }

    @Test
    void プロジェクトの変更はそのプロジェクトのIDと呼び出し元のトークンで依頼する() {
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");

        notifier().notifyProjectChanged(7L);

        verify(projectBridgeClient).requestLetsblogSync(7L, "Bearer t");
    }

    @Test
    void グローバルの変更はプロジェクトIDなしで依頼する() {
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");

        notifier().notifyGlobalChanged();

        verify(projectBridgeClient).requestLetsblogSync(isNull(), eq("Bearer t"));
    }

    @Test
    void 依頼に失敗しても例外を投げない() {
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
        doThrow(new IllegalStateException("down")).when(projectBridgeClient).requestLetsblogSync(any(), anyString());

        notifier().notifyProjectChanged(7L);

        verify(projectBridgeClient).requestLetsblogSync(7L, "Bearer t");
    }

    @Test
    void トランザクション中はコミットされるまで依頼しない() {
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
        TransactionSynchronizationManager.initSynchronization();

        notifier().notifyProjectChanged(7L);

        verifyNoInteractions(projectBridgeClient);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(projectBridgeClient).requestLetsblogSync(7L, "Bearer t");
    }

    @Test
    void ロールバックされたら依頼しない() {
        TransactionSynchronizationManager.initSynchronization();

        notifier().notifyGlobalChanged();

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(projectBridgeClient, never()).requestLetsblogSync(any(), any());
    }
}
