package com.letsblog.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateGithubTokenRequest(
        @NotBlank(message = "Personal Access Token を入力してください")
        String githubToken
) {
}
