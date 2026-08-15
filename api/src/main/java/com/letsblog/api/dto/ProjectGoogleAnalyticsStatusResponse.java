package com.letsblog.api.dto;

/** サービスアカウントJSONそのものは返さず、設定済みかどうかとプロパティID(秘匿情報ではない)のみ返す。 */
public record ProjectGoogleAnalyticsStatusResponse(boolean configured, String propertyId) {
}
