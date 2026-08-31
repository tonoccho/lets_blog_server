package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateGithubTokenRequest(
        @NotBlank(message = "Personal Access Token を入力してください")
        String githubToken
) {
}
