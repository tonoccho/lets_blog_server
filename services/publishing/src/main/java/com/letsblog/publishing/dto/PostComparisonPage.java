package com.letsblog.publishing.dto;

import java.util.List;

public record PostComparisonPage(
        List<PostComparisonRow> items,
        int page,
        int size,
        long totalCount,
        String postType
) {
}
