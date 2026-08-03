package com.letsblog.api.controller;

import com.letsblog.api.domain.Permission;
import com.letsblog.api.dto.UpdateGithubTokenRequest;
import com.letsblog.api.dto.UpdateUserPreferencesRequest;
import com.letsblog.api.dto.UserCreateRequest;
import com.letsblog.api.dto.UserProfileResponse;
import com.letsblog.api.dto.UserProfileUpdateRequest;
import com.letsblog.api.dto.UserResponse;
import com.letsblog.api.dto.UserUpdateRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.PermissionAuthorizationService;
import com.letsblog.api.service.RoleService;
import com.letsblog.api.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @GetMapping
    public List<UserResponse> list() {
        return userService.list();
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @PatchMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @RequestBody UserUpdateRequest request) {
        return userService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public UserProfileResponse getProfile(@PathVariable Long id) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.findUserWithProfile(id);
    }

    @PutMapping("/{id}")
    public UserProfileResponse updateProfile(
            @PathVariable Long id, @RequestBody UserProfileUpdateRequest request) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.updateUserProfile(id, request);
    }

    @PatchMapping("/{id}/preferences")
    public UserProfileResponse updatePreferences(
            @PathVariable Long id, @Valid @RequestBody UpdateUserPreferencesRequest request) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.updateUserPreferences(id, request);
    }

    @PutMapping("/{id}/github-token")
    public UserProfileResponse updateGithubToken(
            @PathVariable Long id, @Valid @RequestBody UpdateGithubTokenRequest request) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.updateGithubToken(id, request);
    }

    @PostMapping("/{userId}/roles/{roleName}")
    public ResponseEntity<Map<String, String>> assignRole(
            @PathVariable Long userId, @PathVariable String roleName) {
        permissionAuthorizationService.requirePermission(Permission.ROLE_MANAGE);
        roleService.assignRoleToUser(userId, roleName);
        return ResponseEntity.ok(Map.of("message", "ロールを割り当てました。"));
    }

    @DeleteMapping("/{userId}/roles/{roleName}")
    public ResponseEntity<Map<String, String>> removeRole(
            @PathVariable Long userId, @PathVariable String roleName) {
        permissionAuthorizationService.requirePermission(Permission.ROLE_MANAGE);
        roleService.removeRoleFromUser(userId, roleName);
        return ResponseEntity.ok(Map.of("message", "ロールを解除しました。"));
    }
}
