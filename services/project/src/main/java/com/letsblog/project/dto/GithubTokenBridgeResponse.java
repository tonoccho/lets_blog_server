package com.letsblog.project.dto;

/**
 * legacy-apiの{@code ProjectApiKeyService}向け内部API応答(issue #577 stage3)。
 * GitHubトークンは暗号化済みバイト列のまま受け渡す(復号はしない。全サービス共通のAPP_ENCRYPTION_KEY
 * で呼び出し元が復号する)。
 */
public record GithubTokenBridgeResponse(boolean configured, byte[] encryptedToken) {
}
