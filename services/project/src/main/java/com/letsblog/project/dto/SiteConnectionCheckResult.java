package com.letsblog.project.dto;

public record SiteConnectionCheckResult(
        boolean connectionOk,
        Boolean hasAdminCapability,
        String failureReason,
        String detail
) {
}
