package com.letsblog.api.service;

import com.letsblog.api.repository.ProjectUserRepository;
import org.springframework.stereotype.Service;

@Service
public class AdminAuthorizationService {

    private final CurrentActorService currentActorService;
    private final ProjectUserRepository projectUserRepository;

    public AdminAuthorizationService(
            CurrentActorService currentActorService, ProjectUserRepository projectUserRepository) {
        this.currentActorService = currentActorService;
        this.projectUserRepository = projectUserRepository;
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

    /**
     * プロジェクトメンバーまたはadmin権限を持つユーザーのみを許可する。
     * プロジェクト単位の機能(壁打ち・issue一覧)で使う。
     */
    public void requireProjectMemberOrAdmin(Long projectId) {
        if (currentActorService.isAdmin()) {
            return;
        }
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        if (projectUserRepository.findByProjectIdAndUserId(projectId, actorId).isEmpty()) {
            throw new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です");
        }
    }
}
