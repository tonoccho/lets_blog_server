package com.letsblog.api.controller;

import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.dto.SaveTagDesignSettingRequest;
import com.letsblog.api.dto.TagDesignSettingResponse;
import com.letsblog.api.dto.TagDesignSettingsOverviewResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.TagDesignSettingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [toc]/[blogcard]/[amazon] 組み込みタグのデザインカスタマイズ画面(プロジェクト単位)向けAPI。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/tag-design-settings")
public class TagDesignSettingController {

    private final TagDesignSettingService tagDesignSettingService;
    private final AdminAuthorizationService adminAuthorizationService;

    public TagDesignSettingController(
            TagDesignSettingService tagDesignSettingService, AdminAuthorizationService adminAuthorizationService) {
        this.tagDesignSettingService = tagDesignSettingService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    public TagDesignSettingsOverviewResponse getOverview(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return tagDesignSettingService.getOverview(projectId);
    }

    @PutMapping("/{tagType}")
    public TagDesignSettingResponse save(
            @PathVariable Long projectId,
            @PathVariable EmbedTagType tagType,
            @Valid @RequestBody SaveTagDesignSettingRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return tagDesignSettingService.save(projectId, tagType, request);
    }
}
