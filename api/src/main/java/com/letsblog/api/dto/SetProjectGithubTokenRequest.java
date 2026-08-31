package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SetProjectGithubTokenRequest(@NotBlank String githubToken) {
}
