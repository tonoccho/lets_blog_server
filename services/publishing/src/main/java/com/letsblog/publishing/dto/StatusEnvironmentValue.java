package com.letsblog.publishing.dto;

/**
 * プラグイン/テーマ比較テーブルの1セル分の値。
 * available=false かつ error=false は、その環境スロットにサイトが紐付いていない・
 * 自動構築サイトでもREST/SSHが設定されたサイトでもないことを表す。
 * available=false かつ error=true は、環境自体は対象だが取得を試みて失敗したことを表す
 * (「対象外」と区別してエラー表示する。errorMessageに詳細を入れる)。
 * available=trueの場合、statusは"NOT_INSTALLED"/"INACTIVE"/"ACTIVE"のいずれか。
 */
public record StatusEnvironmentValue(
        boolean available,
        boolean error,
        String errorMessage,
        String status
) {
    public static StatusEnvironmentValue unavailable() {
        return new StatusEnvironmentValue(false, false, null, null);
    }

    public static StatusEnvironmentValue error(String errorMessage) {
        return new StatusEnvironmentValue(false, true, errorMessage, null);
    }

    public static StatusEnvironmentValue of(String status) {
        return new StatusEnvironmentValue(true, false, null, status);
    }
}
