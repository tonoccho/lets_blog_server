package com.letsblog.media.dto;

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
        /** バッチ内の位置(0起点、issue #1101)。#1101以前に生成した行はnull。 */
        Integer batchIndex,
        String checkpoint,
        String loraName,
        Double loraWeight,
        LocalDateTime createdAt,
        List<String> tags,
        String provider
) {
}
