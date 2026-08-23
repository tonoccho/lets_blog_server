package com.letsblog.identity.dto;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.domain.User;

import java.util.List;

public record PermissionsResponse(Long userId, List<Permission> permissions) {
    public static PermissionsResponse from(User user) {
        return new PermissionsResponse(
                user.getId(),
                user.getRoles().stream()
                        .flatMap(role -> role.getPermissions().stream())
                        .distinct()
                        .sorted()
                        .toList());
    }
}
