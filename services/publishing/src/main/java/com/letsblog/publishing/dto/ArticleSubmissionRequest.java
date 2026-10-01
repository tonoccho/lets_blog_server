package com.letsblog.publishing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 記事提出APIの入力(issue #1339)。スラッグの形は拡張側{@code articleScaffold.ts}の{@code SLUG_PATTERN}と同じ。
 */
public record ArticleSubmissionRequest(
        @NotBlank String headBranch,
        @NotNull @Positive Integer githubIssueNumber,
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$") String articleSlug) {
}
