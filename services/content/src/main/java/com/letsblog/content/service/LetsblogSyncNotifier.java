package com.letsblog.content.service;

import com.letsblog.content.client.ProjectBridgeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * カスタムタグ・CSS・プレフィックスの変更を project-service へ伝え、WordPress のサイトへの同期を依頼する
 * (issue #1558)。project-service は同期のたびに content-service から最新の内容を読み直すため、
 * 依頼は変更がコミットされた後に出す(コミット前だと古い内容を読んでしまう)。
 *
 * <p>依頼の失敗は変更の保存を失敗させない。届かなかった分は、サイト画面の再同期で回復できる。
 */
@Component
@Slf4j
public class LetsblogSyncNotifier {

    private final ProjectBridgeClient projectBridgeClient;
    private final CurrentActorService currentActorService;

    public LetsblogSyncNotifier(ProjectBridgeClient projectBridgeClient, CurrentActorService currentActorService) {
        this.projectBridgeClient = projectBridgeClient;
        this.currentActorService = currentActorService;
    }

    /** そのプロジェクトのすべてのサイトへの同期を依頼する。 */
    public void notifyProjectChanged(Long projectId) {
        afterCommit(projectId);
    }

    /** グローバルタグの変更。すべてのプロジェクトのサイトへの同期を依頼する。 */
    public void notifyGlobalChanged() {
        afterCommit(null);
    }

    private void afterCommit(Long projectId) {
        String bearer = currentActorService.getAuthorizationHeader();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            request(projectId, bearer);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                request(projectId, bearer);
            }
        });
    }

    private void request(Long projectId, String bearer) {
        try {
            projectBridgeClient.requestLetsblogSync(projectId, bearer);
        } catch (RuntimeException e) {
            log.warn("letsblogプラグインへの同期の依頼に失敗しました (projectId={}): {}", projectId, e.getMessage());
        }
    }
}
