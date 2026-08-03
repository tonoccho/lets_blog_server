package com.letsblog.api.dto;

import java.util.List;

public record ComfyUiCheckpointListResponse(List<String> checkpoints, String selected) {
}
