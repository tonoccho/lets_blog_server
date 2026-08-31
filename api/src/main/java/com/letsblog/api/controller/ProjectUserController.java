package com.letsblog.api.controller;

import com.letsblog.api.dto.ProjectUserSummaryResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ProjectUserSyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/project-users")
public class ProjectUserController {

    private final ProjectUserSyncService projectUserSyncService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectUserController(
            ProjectUserSyncService projectUserSyncService,
            AdminAuthorizationService adminAuthorizationService) {
        this.projectUserSyncService = projectUserSyncService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    public List<ProjectUserSummaryResponse> listAll() {
        adminAuthorizationService.requireAdmin();
        return projectUserSyncService.listAllProjectUsers();
    }
}
