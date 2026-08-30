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
     * admin権限を要求したうえで、対象が操作者自身でないことも要求する(issue #796)。
     *
     * <p>アカウント削除のように取り消せない操作で、最後のadminが自分自身を消して
     * 誰も管理できない状態になるのを防ぐ。Web側にも同じガードがあるが
     * ({@code web/src/app/users/actions.ts})、gatewayは認可判定を行わず(ADR-0008)、
     * アクセストークンを持つクライアントはAPIを直接叩けるため、サーバー側にも置く。
     */
    public void requireAdminAndNotSelf(Long userId) {
        requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId != null && actorId.equals(userId)) {
            throw new ForbiddenException("自分自身のアカウントは削除できません");
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
