package com.letsblog.identity.dto;

import com.letsblog.identity.domain.Role;
import com.letsblog.identity.domain.User;

import java.time.Instant;
import java.util.List;

public record UserResponse(
        Long id,
        String email,
        String role,
        List<String> roleNames,
        boolean enabled,
        boolean keycloakLinked,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getRoles().stream().map(Role::getRoleName).sorted().toList(),
                user.isEnabled(),
                user.getKeycloakSub() != null,
                UtcDateTimes.toInstant(user.getCreatedAt()),
                UtcDateTimes.toInstant(user.getUpdatedAt())
        );
    }
}
