package com.letsblog.project.controller;

import com.letsblog.project.service.LetsblogSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * content-service が、タグ・CSS・プレフィックスの変更を WordPress のサイトへの同期として依頼する内部API
 * (issue #1558)。受け付けて直ちに202を返し、同期は後で行う。他の内部ブリッジと同じく、
 * {@code SecurityConfig}の{@code /api/internal/**}の認証必須以上の追加認可は行わない
 * (送るのは既にcontent-serviceに保存済みの内容で、依頼自体は何も書き換えない)。
 */
@RestController
public class LetsblogSyncInternalController {

    private final LetsblogSyncService letsblogSyncService;

    public LetsblogSyncInternalController(LetsblogSyncService letsblogSyncService) {
        this.letsblogSyncService = letsblogSyncService;
    }

    /** @param projectId nullならすべてのプロジェクトが対象(グローバルタグの変更) */
    public record SyncRequest(Long projectId) {
    }

    @PostMapping("/api/internal/project/letsblog-sync")
    public ResponseEntity<Void> request(@RequestBody SyncRequest request) {
        if (request.projectId() == null) {
            letsblogSyncService.requestAllSync();
        } else {
            letsblogSyncService.requestProjectSync(request.projectId());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
