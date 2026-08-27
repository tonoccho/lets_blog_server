package com.letsblog.identity.controller;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.PermissionsResponse;
import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.service.ForbiddenException;
import com.letsblog.identity.service.UserNotFoundException;
import com.letsblog.identity.repository.UserRepository;
import com.letsblog.identity.service.AdminAuthorizationService;
import com.letsblog.identity.service.CurrentActorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CurrentActorServiceが解決するアクター(Keycloak JWTのsubクレーム起点)を起点に、
 * 自分自身の情報・権限を返すAPI(#561)。
 */
@RestController
@RequestMapping("/api/identity")
public class IdentityController {

    private final CurrentActorService currentActorService;
    private final UserRepository userRepository;
    private final AdminAuthorizationService adminAuthorizationService;

    public IdentityController(
            CurrentActorService currentActorService,
            UserRepository userRepository,
            AdminAuthorizationService adminAuthorizationService) {
        this.currentActorService = currentActorService;
        this.userRepository = userRepository;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping("/me")
    public UserProfileResponse me() {
        Long actorId = requireActorId();
        return UserProfileResponse.from(findUser(actorId));
    }

    @GetMapping("/me/permissions")
    public PermissionsResponse myPermissions() {
        Long actorId = requireActorId();
        return PermissionsResponse.from(findUser(actorId));
    }

    @GetMapping("/users/{id}/permissions")
    public PermissionsResponse userPermissions(@PathVariable Long id) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return PermissionsResponse.from(findUser(id));
    }

    private Long requireActorId() {
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作には認証が必要です");
        }
        return actorId;
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));
    }
}
