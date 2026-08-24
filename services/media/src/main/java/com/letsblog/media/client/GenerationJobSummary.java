package com.letsblog.media.client;

import java.time.LocalDateTime;

/** legacy-apiの{@code GenerationJobResponse}を写したもの(#573 stage3、GenerationJobClient参照)。 */
public record GenerationJobSummary(Long id, String type, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
