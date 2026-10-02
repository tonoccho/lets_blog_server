package com.letsblog.project.dto;

import com.letsblog.project.domain.SshKeyPair;

import java.time.Instant;

/**
 * 鍵ペア生成直後のみ返す秘密鍵付きレスポンス。保存後はこのレスポンスからのみ秘密鍵を取得でき、
 * 一覧・詳細取得APIでは二度と返さない。
 */
public record SshKeyPairGeneratedResponse(
        Long id,
        String name,
        String comment,
        String publicKeyLine,
        String privateKeyPem,
        Instant createdAt) {

    public static SshKeyPairGeneratedResponse of(SshKeyPair entity, String privateKeyPem) {
        return new SshKeyPairGeneratedResponse(
                entity.getId(), entity.getName(), entity.getComment(),
                entity.getPublicKeyLine(), privateKeyPem, UtcDateTimes.toInstant(entity.getCreatedAt()));
    }
}
