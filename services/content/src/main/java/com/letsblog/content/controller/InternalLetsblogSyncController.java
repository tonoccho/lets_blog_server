package com.letsblog.content.controller;

import com.letsblog.content.dto.LetsblogSyncPayloadResponse;
import com.letsblog.content.service.LetsblogSyncPayloadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * project-service が、WordPress の letsblog プラグインへ送る内容(タグ定義・統合CSS・プレフィックス・
 * デザイン)とそのハッシュを、同期のたびに最新の状態で読む内部ブリッジ(issue #1558)。
 * 認可は呼び出し元のBearerトークンでの各サービス内の判定(プロジェクトメンバーまたはadmin)に従う。
 */
@RestController
public class InternalLetsblogSyncController {

    private final LetsblogSyncPayloadService payloadService;

    public InternalLetsblogSyncController(LetsblogSyncPayloadService payloadService) {
        this.payloadService = payloadService;
    }

    /** 認可不要: 内部ブリッジ。呼び出し元のBearerトークンで、組み立て中の各サービスがプロジェクトメンバー判定を行う。 */
    @GetMapping("/api/internal/content/projects/{projectId}/letsblog-sync-payload")
    public LetsblogSyncPayloadResponse payload(@PathVariable Long projectId) {
        return payloadService.build(projectId);
    }
}
