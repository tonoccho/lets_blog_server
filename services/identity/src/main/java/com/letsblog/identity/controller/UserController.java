package com.letsblog.identity.controller;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.dto.UpdateGithubTokenRequest;
import com.letsblog.identity.dto.UpdateUserPreferencesRequest;
import com.letsblog.identity.dto.UserCreateRequest;
import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.dto.UserProfileUpdateRequest;
import com.letsblog.identity.dto.UserResponse;
import com.letsblog.identity.dto.UserUpdateRequest;
import com.letsblog.identity.service.AdminAuthorizationService;
import com.letsblog.identity.service.PermissionAuthorizationService;
import com.letsblog.identity.service.RoleService;
import com.letsblog.identity.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Users", description = "ユーザー管理API")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final RoleService roleService;
    private final PermissionAuthorizationService permissionAuthorizationService;
    private final AdminAuthorizationService adminAuthorizationService;

    public UserController(
            UserService userService,
            RoleService roleService,
            PermissionAuthorizationService permissionAuthorizationService,
            AdminAuthorizationService adminAuthorizationService) {
        this.userService = userService;
        this.roleService = roleService;
        this.permissionAuthorizationService = permissionAuthorizationService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @Operation(summary = "全ユーザー一覧を取得", description = "システムに登録されているすべてのユーザーを取得します")
    @ApiResponse(responseCode = "200", description = "ユーザー一覧を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @GetMapping
    public List<UserResponse> list() {
        return userService.list();
    }

    @Operation(summary = "ユーザーを新規作成", description = "新しいユーザーアカウントを作成します")
    @ApiResponse(responseCode = "201", description = "ユーザーが作成されました")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @Operation(summary = "ユーザー情報を更新", description = "指定されたユーザーの情報を部分更新します")
    @ApiResponse(responseCode = "200", description = "ユーザーが更新されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @PatchMapping("/{id}")
    public UserResponse update(
            @Parameter(description = "ユーザーID") @PathVariable Long id,
            @RequestBody UserUpdateRequest request) {
        return userService.update(id, request);
    }

    @Operation(summary = "ユーザーを削除", description = "指定されたユーザーを削除します")
    @ApiResponse(responseCode = "204", description = "ユーザーが削除されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "ユーザーID") @PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "ユーザープロフィール取得", description = "指定されたユーザーのプロフィール情報を取得します")
    @ApiResponse(responseCode = "200", description = "プロフィール情報を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @GetMapping("/{id}")
    public UserProfileResponse getProfile(@Parameter(description = "ユーザーID") @PathVariable Long id) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.findUserWithProfile(id);
    }

    @Operation(summary = "ユーザープロフィール更新", description = "指定されたユーザーのプロフィール情報を更新します")
    @ApiResponse(responseCode = "200", description = "プロフィールが更新されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @PutMapping("/{id}")
    public UserProfileResponse updateProfile(
            @Parameter(description = "ユーザーID") @PathVariable Long id,
            @RequestBody UserProfileUpdateRequest request) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.updateUserProfile(id, request);
    }

    @Operation(summary = "ユーザー設定を更新", description = "指定されたユーザーの各種設定を更新します")
    @ApiResponse(responseCode = "200", description = "設定が更新されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @PatchMapping("/{id}/preferences")
    public UserProfileResponse updatePreferences(
            @Parameter(description = "ユーザーID") @PathVariable Long id,
            @Valid @RequestBody UpdateUserPreferencesRequest request) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.updateUserPreferences(id, request);
    }

    @Operation(summary = "GitHubトークンを更新", description = "指定されたユーザーのGitHubトークンを更新します")
    @ApiResponse(responseCode = "200", description = "トークンが更新されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @PutMapping("/{id}/github-token")
    public UserProfileResponse updateGithubToken(
            @Parameter(description = "ユーザーID") @PathVariable Long id,
            @Valid @RequestBody UpdateGithubTokenRequest request) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.updateGithubToken(id, request);
    }

    @Operation(summary = "ロールを割り当て", description = "指定されたユーザーにロールを割り当てます")
    @ApiResponse(responseCode = "200", description = "ロールが割り当てられました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "ロール管理権限がありません")
    @PostMapping("/{userId}/roles/{roleName}")
    public ResponseEntity<Map<String, String>> assignRole(
            @Parameter(description = "ユーザーID") @PathVariable Long userId,
            @Parameter(description = "ロール名") @PathVariable String roleName) {
        permissionAuthorizationService.requirePermission(Permission.ROLE_MANAGE);
        roleService.assignRoleToUser(userId, roleName);
        return ResponseEntity.ok(Map.of("message", "ロールを割り当てました。"));
    }

    @Operation(summary = "ロールを削除", description = "指定されたユーザーからロールを削除します")
    @ApiResponse(responseCode = "200", description = "ロールが削除されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "ロール管理権限がありません")
    @DeleteMapping("/{userId}/roles/{roleName}")
    public ResponseEntity<Map<String, String>> removeRole(
            @Parameter(description = "ユーザーID") @PathVariable Long userId,
            @Parameter(description = "ロール名") @PathVariable String roleName) {
        permissionAuthorizationService.requirePermission(Permission.ROLE_MANAGE);
        roleService.removeRoleFromUser(userId, roleName);
        return ResponseEntity.ok(Map.of("message", "ロールを解除しました。"));
    }
}
