package com.letsblog.api.dto;

/**
 * リフレッシュトークン/クライアントシークレットそのものは返さず、設定済みかどうかとアカウントID/
 * クライアントID(いずれも秘匿情報ではない)のみ返す(issue #407)。
 */
public record ProjectAdSenseStatusResponse(boolean configured, String accountId, String clientId, boolean hasClientSecret) {
}
