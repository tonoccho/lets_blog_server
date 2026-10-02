package com.letsblog.media.controller;

import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.media.dto.MediaGarbageCollectionDeleteRequest;
import com.letsblog.media.dto.MediaGarbageCollectionScanResponse;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.CurrentActorService;
import com.letsblog.media.service.MediaGarbageCollectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiから移設(issue #573 stage3)。プロジェクト画面の「ガベージコレクション」タブ向けAPI
 * (issue #500)。パスはlegacy-api時代と同一(gatewayに新規ルート
 * "/api/projects/{id}/media-garbage-collection/scan,delete" を追加してmedia-serviceへ向けた。
 * 通常の "/api/projects/**" は"project"グループでlegacy-apiのまま)。
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
        return mediaGarbageCollectionService.scan(id, environment, currentActorService.getAuthorizationHeader());
    }

    @PostMapping("/delete")
    public GenerationJobSummary delete(@PathVariable Long id, @RequestParam String environment,
            @Valid @RequestBody MediaGarbageCollectionDeleteRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        String actorKeycloakSub = currentActorService.getCurrentActorKeycloakSub();
        return mediaGarbageCollectionService.startDelete(
                id, environment, request.mediaIds(), actorId, actorKeycloakSub,
                currentActorService.getAuthorizationHeader());
    }
}
