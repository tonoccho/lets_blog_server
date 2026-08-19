package com.letsblog.api.dto;

/**
 * アクセストークンそのものは返さず、設定済みかどうか(configured)と、秘匿情報ではない
 * 他の項目(有効/無効・プロファイルID・遅延分数・メッセージテンプレート)を返す。
 */
public record ProjectBufferStatusResponse(
        boolean configured,
        boolean enabled,
        boolean hasAccessToken,
        String profileIds,
        Integer delayMinutes,
        String messageTemplate
) {
}
