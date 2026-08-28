package com.letsblog.publishing.service;

import org.springframework.stereotype.Service;

/**
 * legacy-apiの{@code com.letsblog.api.service.AdminAuthorizationService}のうち、
 * {@code com.letsblog.publishing.controller.BulkManagementController}が使う{@code requireAdmin}のみを
 * publishing-serviceへ移設したもの(issue #708)。{@code requireSelfOrAdmin}/{@code requireProjectMemberOrAdmin}は
 * {@code project_user}(legacy-api所有)への依存を伴い、本サービスの一括管理機能では使わないため
 * 移設しない。
 */
@Service
public class AdminAuthorizationService {

    private final CurrentActorService currentActorService;

    public AdminAuthorizationService(CurrentActorService currentActorService) {
        this.currentActorService = currentActorService;
    }

    public void requireAdmin() {
        if (!currentActorService.isAdmin()) {
            throw new ForbiddenException("この操作にはadmin権限が必要です");
        }
    }
}
