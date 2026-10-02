package com.letsblog.project.dto;

import com.letsblog.project.domain.SshKeyPair;

import java.time.Instant;

public record SshKeyPairSummaryResponse(
        Long id,
        String name,
        String comment,
        String publicKeyLine,
        Instant createdAt) {

    public static SshKeyPairSummaryResponse from(SshKeyPair entity) {
        return new SshKeyPairSummaryResponse(
                entity.getId(), entity.getName(), entity.getComment(),
                entity.getPublicKeyLine(), UtcDateTimes.toInstant(entity.getCreatedAt()));
    }
}
