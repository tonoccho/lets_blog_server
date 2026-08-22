package com.letsblog.api.dto;

import com.letsblog.api.crypto.SshKeyGenerationService.SshKeyPair;

public record SshKeyPairResponse(String publicKeyLine, String privateKeyPem) {

    public static SshKeyPairResponse from(SshKeyPair keyPair) {
        return new SshKeyPairResponse(keyPair.publicKeyLine(), keyPair.privateKeyPem());
    }
}
