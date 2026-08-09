package com.letsblog.api.controller;

import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.CustomTagService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * プロジェクト詳細のカスタムタグ画面向けAPI。グローバルタグを含まず、プロジェクトのタグのみを返す(issue #157)。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/custom-tags")
public class ProjectCustomTagController {

    private final CustomTagService customTagService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectCustomTagController(
            CustomTagService customTagService, AdminAuthorizationService adminAuthorizationService) {
        this.customTagService = customTagService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    public List<CustomTagResponse> list(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return customTagService.listByProject(projectId);
    }

    @GetMapping("/css-bundle")
    public ResponseEntity<byte[]> cssBundle(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        byte[] css = customTagService.buildProjectCssBundle(projectId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/css"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"custom-tags.css\"")
                .body(css);
    }
}
