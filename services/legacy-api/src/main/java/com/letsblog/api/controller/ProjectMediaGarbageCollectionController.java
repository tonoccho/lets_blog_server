package com.letsblog.api.controller;

import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.dto.MediaGarbageCollectionDeleteRequest;
import com.letsblog.api.dto.MediaGarbageCollectionScanResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.MediaGarbageCollectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト画面の「ガベージコレクション」タブ向けAPI(issue #500)。
 * 選択した環境のWordPressサイトから、投稿本文・アイキャッチ・主要サイト設定のいずれからも
 * 参照されていないメディアを検出(scan)し、選択削除(delete、非同期ジョブ)する。
 */
@RestController
@RequestMapping("/api/projects/{id}/media-garbage-collection")
public class ProjectMediaGarbageCollectionController {

    private final MediaGarbageCollectionService mediaGarbageCollectionService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;

    public ProjectMediaGarbageCollectionController(
            MediaGarbageCollectionService mediaGarbageCollectionService,
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService) {
        this.mediaGarbageCollectionService = mediaGarbageCollectionService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
    }

    @GetMapping("/scan")
    public MediaGarbageCollectionScanResponse scan(@PathVariable Long id, @RequestParam String environment) {
        adminAuthorizationService.requireAdmin();
        return mediaGarbageCollectionService.scan(id, environment);
    }

    @PostMapping("/delete")
    public GenerationJobResponse delete(@PathVariable Long id, @RequestParam String environment,
            @Valid @RequestBody MediaGarbageCollectionDeleteRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        String actorKeycloakSub = currentActorService.getCurrentActorKeycloakSub();
        return mediaGarbageCollectionService.startDelete(id, environment, request.mediaIds(), actorId, actorKeycloakSub);
    }
}
