package com.letsblog.api.dto;

import java.util.List;

public record TaxonomyResolveRequest(String site, List<String> categories, List<String> tags) {
}
