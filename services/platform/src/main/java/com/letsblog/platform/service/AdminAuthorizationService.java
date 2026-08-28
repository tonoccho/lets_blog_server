package com.letsblog.platform.service;

import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、SystemSettingService/AppSettingServiceが必要とする
 * requireAdminのみを移設する(issue #693)。実際のadmin判定はCurrentActorService経由で
 * identity-serviceへ委ねる。
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
