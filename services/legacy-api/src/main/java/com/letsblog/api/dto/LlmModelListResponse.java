package com.letsblog.api.dto;

import java.util.List;

public record LlmModelListResponse(List<String> availableModels, String selected) {
}
