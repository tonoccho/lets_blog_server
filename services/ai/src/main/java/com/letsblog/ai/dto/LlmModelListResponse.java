package com.letsblog.ai.dto;

import java.util.List;

public record LlmModelListResponse(List<String> availableModels, String selected) {
}
