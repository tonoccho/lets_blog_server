package com.letsblog.api.dto;

import com.letsblog.api.ai.OllamaModelInfo;

import java.util.List;

public record OllamaModelListResponse(List<OllamaModelInfo> models, String selected) {
}
