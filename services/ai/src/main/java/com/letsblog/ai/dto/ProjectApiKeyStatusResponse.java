package com.letsblog.ai.dto;

/** 値そのものは返さず、設定済みかどうかのみ返す(issue #583でlegacy-apiから移設)。 */
public record ProjectApiKeyStatusResponse(boolean configured) {
}
