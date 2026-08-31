package com.letsblog.api.dto;

/** valueそのものは返さず、設定済みかどうかのみ返す(SiteDetailのconfiguredSecretFieldsと同じ方針)。 */
public record ProjectApiKeyStatusResponse(boolean configured) {
}
