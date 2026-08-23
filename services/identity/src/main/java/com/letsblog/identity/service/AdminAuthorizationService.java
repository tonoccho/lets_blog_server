package com.letsblog.identity.service;

import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、users/roles管理に必要な部分のみを移設(#561)。
 * requireProjectMemberOrAdmin相当のプロジェクト単位のチェックはproject/site領域に依存するため
 * legacy-apiに残したまま(Phase 19のサービス抽出Issueで改めて整理する)。
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

    /**
     * 本人のリソース操作、またはadmin権限を持つ操作者のみを許可する。
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
