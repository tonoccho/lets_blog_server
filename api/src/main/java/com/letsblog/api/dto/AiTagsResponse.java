package com.letsblog.api.dto;

import java.util.List;

public record AiTagsResponse(List<String> categories, List<String> tags) {
}
