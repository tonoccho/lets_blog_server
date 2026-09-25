package com.letsblog.ai.dto;

import java.util.List;

public record AiSectionResponse(String result, List<SourceReference> sources, String searchNote) {
}
