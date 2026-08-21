package com.letsblog.api.dto;

import java.time.LocalDateTime;
import java.util.List;

public record GeneratedImageDetailResponse(
        Long id,
        Long projectId,
        String prompt,
        String negativePrompt,
        Integer steps,
        Double cfgScale,
        String samplerName,
        String scheduler,
        Long seed,
        Integer width,
        Integer height,
        Integer batchSize,
        String checkpoint,
        String loraName,
        Double loraWeight,
        LocalDateTime createdAt,
        List<String> tags,
        String provider
) {
}
