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

    /**
     * 本人のリソース操作、またはadmin権限を持つ操作者のみを許可する。
     * ユーザープロフィールの参照・更新のように「自分自身は操作できるがadminも代理操作できる」箇所で使う。
     */
    public void requireSelfOrAdmin(Long userId) {
        if (currentActorService.isAdmin()) {
            return;
        }
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null || !actorId.equals(userId)) {
            throw new ForbiddenException("この操作には本人またはadmin権限が必要です");
        }
    }
}
