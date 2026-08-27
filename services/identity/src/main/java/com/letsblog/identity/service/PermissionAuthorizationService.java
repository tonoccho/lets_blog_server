package com.letsblog.identity.service;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * 細粒度権限(Permission)のチェックを行う。
 * 「誰がリクエストしているか」はCurrentActorServiceが解決するKeycloak JWTのsubクレーム起点の
 * actorIdのみを信頼し、実際に持つ権限はDB上のUser.rolesをサーバー側で都度検索して判定する。
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
