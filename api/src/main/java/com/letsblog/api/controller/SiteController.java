package com.letsblog.api.controller;

import com.letsblog.api.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.api.dto.SiteConnectionCheckResult;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.dto.SiteUpdateRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.ProvisioningService;
import com.letsblog.api.service.SiteService;
import com.letsblog.api.service.WordPressSiteProvisioningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final SiteService siteService;
    private final CurrentActorService currentActorService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final WordPressSiteProvisioningService wordPressSiteProvisioningService;

    public SiteController(
            SiteService siteService,
            CurrentActorService currentActorService,
            AdminAuthorizationService adminAuthorizationService,
            WordPressSiteProvisioningService wordPressSiteProvisioningService) {
        this.siteService = siteService;
        this.currentActorService = currentActorService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.wordPressSiteProvisioningService = wordPressSiteProvisioningService;
    }

    @PostMapping
    public ResponseEntity<SiteResponse> register(@Valid @RequestBody SiteRegisterRequest request) {
        Long actorId = currentActorService.getCurrentActorId();
        return ResponseEntity.status(HttpStatus.CREATED).body(siteService.register(request, actorId));
    }

    @PostMapping("/managed-wordpress")
    public ResponseEntity<SiteResponse> createManagedWordPress(
            @Valid @RequestBody CreateManagedWordPressSiteRequest request) {
        Long actorId = currentActorService.getCurrentActorId();
        SiteResponse response = wordPressSiteProvisioningService.createManagedSite(request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<SiteResponse> list() {
        return siteService.list();
    }

    @PutMapping("/{id}")
    public SiteResponse update(@PathVariable Long id, @RequestBody SiteUpdateRequest request) {
        adminAuthorizationService.requireAdmin();
        return siteService.update(id, request);
    }

    @PostMapping("/{id}/test-connection")
    public Map<String, Object> testConnection(@PathVariable Long id) {
        SiteConnectionCheckResult result = siteService.checkConnection(id);
        Map<String, Object> response = new HashMap<>();
        response.put("connectionCheckStatus", result.connectionOk() ? "SUCCESS" : "FAILED");
        response.put("hasAdminCapability", result.hasAdminCapability());
        return response;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        wordPressSiteProvisioningService.deleteSite(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reprovision")
    public ResponseEntity<Map<String, String>> reprovision(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        ProvisioningService.ProvisioningResult result = siteService.reprovision(id, actorId);

        String message = "プロビジョニングを再実行しました。カテゴリ: "
                + (result.defaultCategoryId != null ? result.defaultCategoryId : "失敗")
                + " / タグ: " + (result.defaultTagId != null ? result.defaultTagId : "失敗")
                + " / 著者: " + (result.authorId != null ? result.authorId : "未対応または失敗");
        return ResponseEntity.ok(Map.of("message", message));
    }
}
