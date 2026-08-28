package com.letsblog.publishing.dto;

import java.util.List;

public record TaxonomyResolveResponse(List<String> categoryIds, List<String> tagIds) {
}
