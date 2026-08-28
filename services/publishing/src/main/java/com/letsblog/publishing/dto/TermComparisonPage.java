package com.letsblog.publishing.dto;

import java.util.List;

public record TermComparisonPage(
        List<TermComparisonRow> items,
        int page,
        int size,
        long totalCount,
        String masterEnvironment
) {
}
