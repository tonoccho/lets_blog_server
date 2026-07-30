package com.letsblog.api.dto;

public record SiteConnectionCheckResult(
        boolean connectionOk,
        Boolean hasAdminCapability
) {
}
