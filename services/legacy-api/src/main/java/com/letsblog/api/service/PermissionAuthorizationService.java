package com.letsblog.api.service;

import com.letsblog.api.domain.Permission;
import com.letsblog.api.domain.User;
import com.letsblog.api.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * 細粒度権限(Permission)のチェックを行う。
 * このAPIサーバーはBFF(Next.js)から転送されるX-Actor-Id/X-Actor-Roleヘッダを信頼するモデルのため、
 * 「誰がリクエストしているか」はヘッダのactorIdのみを信頼し、実際に持つ権限はDB上のUser.rolesを
 * サーバー側で都度検索して判定する(ヘッダのroleは admin/非admin の一次判定にのみ用いる)。
 */
@Service
public class PermissionAuthorizationService {

    private final CurrentActorService currentActorService;
    private final UserRepository userRepository;

    public PermissionAuthorizationService(CurrentActorService currentActorService, UserRepository userRepository) {
        this.currentActorService = currentActorService;
        this.userRepository = userRepository;
    }

    public void requirePermission(Permission permission) {
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作には認証が必要です");
        }

        User user = userRepository.findById(actorId)
                .orElseThrow(() -> new ForbiddenException("この操作には認証が必要です"));

        if (!user.hasPermission(permission)) {
            throw new ForbiddenException("この操作には権限 '" + permission + "' が必要です");
        }
    }
}
