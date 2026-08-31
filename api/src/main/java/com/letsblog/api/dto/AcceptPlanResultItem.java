package com.letsblog.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AcceptPlanResultItem(
        String title,
        Integer issueNumber,
        String issueUrl,
        String error
) {
}
