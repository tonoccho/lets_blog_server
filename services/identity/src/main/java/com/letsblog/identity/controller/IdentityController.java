package com.letsblog.identity.controller;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.PermissionsResponse;
import com.letsblog.identity.dto.UpdateUserPreferencesRequest;
import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.service.ForbiddenException;
import com.letsblog.identity.service.UserNotFoundException;
import com.letsblog.identity.repository.UserRepository;
import com.letsblog.identity.service.AdminAuthorizationService;
import com.letsblog.identity.service.CurrentActorService;
import com.letsblog.identity.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final UserService userService;

    public IdentityController(
            CurrentActorService currentActorService,
            UserRepository userRepository,
            AdminAuthorizationService adminAuthorizationService,
            UserService userService) {
        this.currentActorService = currentActorService;
        this.userRepository = userRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.userService = userService;
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

    /**
     * 自分自身の個人設定(言語・タイムゾーン)を更新する(issue #784)。
     *
     * <p>{@code PATCH /api/users/{id}/preferences}(UserController)と同じ処理を、
     * <b>クライアントからユーザーIDを受け取らずに</b>行う。Web(Next.js)はKeycloak移行(#564)以降
     * セッションに保持しているのがKeycloakの{@code sub}(UUID)であり、これを数値IDへ変換すると
     * {@code NaN}になるため、{@code PATCH /api/users/NaN/preferences}という壊れたリクエストを
     * 送り続けていた(issue #784。24時間で203件の{@code /api/users/NaN}が観測された)。
     *
     * <p>自ユーザーの解決は{@link CurrentActorService}に委ねる。検証済みJWTの{@code sub}から
     * ローカル{@code User}を引き当てるため、クライアント入力の識別子を一切受け取らない。
     * ID指定版({@code /api/users/{id}/preferences})はadminが他ユーザーを操作する経路のため残す。
     */
    @PatchMapping("/me/preferences")
    public UserProfileResponse updateMyPreferences(@Valid @RequestBody UpdateUserPreferencesRequest request) {
        Long actorId = requireActorId();
        return userService.updateUserPreferences(actorId, request);
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
