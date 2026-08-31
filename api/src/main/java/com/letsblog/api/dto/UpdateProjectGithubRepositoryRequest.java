package com.letsblog.api.dto;

import jakarta.validation.constraints.Pattern;

public record UpdateProjectGithubRepositoryRequest(
        @Pattern(
                regexp = "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$|^$",
                message = "リポジトリは 'owner/repo' 形式で指定してください(空で紐付け解除)"
        )
        String githubRepository
) {
}
