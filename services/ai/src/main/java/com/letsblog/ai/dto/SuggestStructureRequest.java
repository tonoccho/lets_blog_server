package com.letsblog.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SuggestStructureRequest(
        @Valid @NotNull List<PlanChatMessage> history
) {
}
