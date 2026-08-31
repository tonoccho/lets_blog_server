package com.letsblog.media.dto;

import java.util.List;

public record ComfyUiCheckpointListResponse(List<String> checkpoints, String selected) {
}
