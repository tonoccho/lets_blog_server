package com.letsblog.api.dto;

public record LoginResponse(UserResponse user, boolean twoFactorRequired, String apiKey) {
}
