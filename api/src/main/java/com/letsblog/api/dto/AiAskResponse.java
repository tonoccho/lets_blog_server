package com.letsblog.api.dto;

import java.util.List;

public record AiAskResponse(String result, List<SourceReference> sources, String searchNote) {
}
