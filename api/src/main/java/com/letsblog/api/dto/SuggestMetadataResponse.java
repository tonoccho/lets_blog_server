package com.letsblog.api.dto;

import java.util.List;

public record SuggestMetadataResponse(
        String title,
        String slug,
        List<String> categories,
        List<String> tags
) {
}
