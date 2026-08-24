package com.letsblog.ai.dto;

import java.util.List;

public record AiDraftResponse(String result, List<SourceReference> sources, String searchNote) {
}
