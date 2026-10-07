package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * はてなの認可画面から戻ったときの state・リクエストトークン(oauth_token)・verifier(oauth_verifier)(issue #1582)。
 * {@code toString} は verifier を出さない。
 */
public record HatenaCallbackRequest(@NotBlank String state, @NotBlank String oauthToken, @NotBlank String oauthVerifier) {

    @Override
    public String toString() {
        return "HatenaCallbackRequest[state=" + state + "]";
    }
}
