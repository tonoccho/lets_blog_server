package com.letsblog.api.dto;

/**
 * プラグイン/テーマ比較テーブルの1セル分の値。
 * available=falseは、その環境スロットにサイトが紐付いていない・自動構築サイトでないことを表す。
 * available=trueの場合、statusは"NOT_INSTALLED"/"INACTIVE"/"ACTIVE"のいずれか。
 */
public record StatusEnvironmentValue(
        boolean available,
        String status
) {
    public static StatusEnvironmentValue unavailable() {
        return new StatusEnvironmentValue(false, null);
    }

    public static StatusEnvironmentValue of(String status) {
        return new StatusEnvironmentValue(true, status);
    }
}
