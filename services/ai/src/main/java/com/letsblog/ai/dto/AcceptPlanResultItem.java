package com.letsblog.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AcceptPlanResultItem(
        String title,
        Integer issueNumber,
        String issueUrl,
        String error
) {
}
