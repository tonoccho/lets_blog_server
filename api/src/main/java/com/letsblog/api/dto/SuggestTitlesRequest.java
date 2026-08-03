package com.letsblog.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SuggestTitlesRequest(
        @Valid @NotNull List<PlanChatMessage> history
) {
}
