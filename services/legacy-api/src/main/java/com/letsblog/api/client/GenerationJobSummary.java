package com.letsblog.api.client;

import java.time.LocalDateTime;

/** ai-serviceの{@code GenerationJobResponse}を写したもの(issue #574、GenerationJobClient参照)。 */
public record GenerationJobSummary(Long id, String type, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
