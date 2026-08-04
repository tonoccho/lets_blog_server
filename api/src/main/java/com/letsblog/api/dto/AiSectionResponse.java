package com.letsblog.api.dto;

import java.util.List;

public record AiSectionResponse(String result, List<SourceReference> sources, String searchNote) {
}
