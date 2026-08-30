package com.letsblog.identity.dto;

/**
 * role/password はどちらも省略可(nullの場合は既存値を維持する)。
 */
public record UserUpdateRequest(
        String role,
        String password
) {
}
