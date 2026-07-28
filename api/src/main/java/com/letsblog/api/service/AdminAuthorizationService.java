package com.letsblog.api.service;

import org.springframework.stereotype.Service;

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
