package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * X の認可の開始(issue #1574)。クライアントの情報はこの認可の間だけメモリに持ち、保存しない。
 * {@code toString} は秘密を出さない。
 */
public record XAuthorizeRequest(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String redirectUri) {

    @Override
    public String toString() {
        return "XAuthorizeRequest[clientId=" + clientId + "]";
    }
}
