package com.letsblog.api.dto;

import java.util.List;

public record ImageGenerationOptionsResponse(
        List<String> checkpoints,
        String selectedCheckpoint,
        List<String> samplers,
        List<String> schedulers,
        List<String> loras
) {
}
