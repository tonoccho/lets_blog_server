package com.letsblog.ai.dto;

import java.util.List;

public record SuggestMetadataResponse(
        List<String> titles,
        List<String> slugs,
        List<String> categories,
        List<String> tags
) {
}
