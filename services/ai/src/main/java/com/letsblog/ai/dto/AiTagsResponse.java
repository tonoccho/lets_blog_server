package com.letsblog.ai.dto;

import java.util.List;

public record AiTagsResponse(List<String> categories, List<String> tags) {
}
