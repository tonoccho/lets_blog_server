package com.letsblog.api.dto;

import com.letsblog.api.domain.User;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String email,
        String role,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
