package com.letsblog.project.dto;

import com.letsblog.project.crypto.SshKeyGenerationService.SshKeyPair;

public record SshKeyPairResponse(String publicKeyLine, String privateKeyPem) {

    public static SshKeyPairResponse from(SshKeyPair keyPair) {
        return new SshKeyPairResponse(keyPair.publicKeyLine(), keyPair.privateKeyPem());
    }
}
