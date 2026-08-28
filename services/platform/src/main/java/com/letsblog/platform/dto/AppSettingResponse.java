package com.letsblog.platform.dto;

/**
 * 秘匿情報(secret=true)は値そのものを返さず、設定済みかどうか(configured)と設定元(source)のみを返す。
 * 秘匿情報でない項目はvalueに現在の値(DB設定または環境変数のいずれか)を含める。
 */
public record AppSettingResponse(
        String key,
        String label,
        boolean secret,
        boolean configured,
        String source,
        String value
) {
}
