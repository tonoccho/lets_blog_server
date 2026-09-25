package com.letsblog.identity.dto;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.domain.Role;

import java.util.Set;

public record RoleResponse(
        Long id,
        String roleName,
        String displayName,
        String description,
        Set<Permission> permissions
) {
    public static RoleResponse from(Role role) {
        return new RoleResponse(
                role.getId(),
                role.getRoleName(),
                role.getDisplayName(),
                role.getDescription(),
                role.getPermissions());
    }
}
