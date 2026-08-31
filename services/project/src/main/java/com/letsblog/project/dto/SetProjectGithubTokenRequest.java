package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;

public record SetProjectGithubTokenRequest(@NotBlank String githubToken) {
}
