package com.letsblog.project.dto;

import java.util.Map;

public record SiteUpdateRequest(
        String name,
        Map<String, String> credentials
) {
}
