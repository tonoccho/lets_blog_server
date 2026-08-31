package com.letsblog.api.dto;

import com.letsblog.api.domain.SshKeyPair;

import java.time.LocalDateTime;

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
        LocalDateTime createdAt) {

    public static SshKeyPairGeneratedResponse of(SshKeyPair entity, String privateKeyPem) {
        return new SshKeyPairGeneratedResponse(
                entity.getId(), entity.getName(), entity.getComment(),
                entity.getPublicKeyLine(), privateKeyPem, entity.getCreatedAt());
    }
}
