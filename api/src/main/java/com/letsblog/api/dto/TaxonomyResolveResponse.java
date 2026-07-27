package com.letsblog.api.dto;

import java.util.List;

public record TaxonomyResolveResponse(List<Long> categoryIds, List<Long> tagIds) {
}
