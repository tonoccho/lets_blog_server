package com.letsblog.project.dto;

/** legacy-apiの{@code ProjectApiKeyService}向け内部API要求(issue #577 stage3)。 */
public record SetGithubTokenBridgeRequest(byte[] encryptedToken) {
}
