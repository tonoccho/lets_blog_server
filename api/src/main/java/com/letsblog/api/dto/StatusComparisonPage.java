package com.letsblog.api.dto;

import java.util.List;

public record StatusComparisonPage(
        List<StatusComparisonRow> items,
        int page,
        int size,
        long totalCount,
        String masterEnvironment
) {
}
