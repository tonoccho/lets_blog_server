package com.letsblog.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PlanChatRequest(
        @Valid @NotNull List<PlanChatMessage> history,
        @NotBlank String message,
        Long sessionId,
        Integer githubIssueNumber
) {
}
