package com.letsblog.api.dto;

import java.util.List;

public record TaxonomyResolveResponse(List<String> categoryIds, List<String> tagIds) {
}
