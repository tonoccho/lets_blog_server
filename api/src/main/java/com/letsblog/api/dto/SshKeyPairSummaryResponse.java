package com.letsblog.api.dto;

import com.letsblog.api.domain.SshKeyPair;

import java.time.LocalDateTime;

public record SshKeyPairSummaryResponse(
        Long id,
        String name,
        String comment,
        String publicKeyLine,
        LocalDateTime createdAt) {

    public static SshKeyPairSummaryResponse from(SshKeyPair entity) {
        return new SshKeyPairSummaryResponse(
                entity.getId(), entity.getName(), entity.getComment(),
                entity.getPublicKeyLine(), entity.getCreatedAt());
    }
}
