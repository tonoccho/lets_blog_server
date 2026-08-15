package com.letsblog.api.dto;

/** リフレッシュトークンそのものは返さず、設定済みかどうかとアカウントID(秘匿情報ではない)のみ返す。 */
public record ProjectAdSenseStatusResponse(boolean configured, String accountId) {
}
