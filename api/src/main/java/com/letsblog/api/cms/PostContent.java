package com.letsblog.api.cms;

import java.util.List;

public record PostContent(
        String title,
        String slug,
        String htmlContent,
        String status,
        List<String> categoryIds,
        List<String> tagIds,
        String featuredMediaId,
        String authorId
) {
}
