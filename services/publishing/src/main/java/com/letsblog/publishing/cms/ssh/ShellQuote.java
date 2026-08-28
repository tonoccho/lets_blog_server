package com.letsblog.publishing.cms.ssh;

/**
 * SSH経由でwp-cliコマンドを組み立てる際のPOSIXシェル向けクォート処理。
 * SSHのexecはリモートシェルが解釈する1本の文字列を送るだけなので、
 * コマンド文字列へ埋め込む動的な値は必ずこれを通す(コマンドインジェクション対策)。
 */
public final class ShellQuote {

    private ShellQuote() {
    }

    /**
     * 値をシングルクォートで囲む。値中の`'`は`'\''`に置換する(POSIXシェルの標準的な安全策)。
     */
    public static String single(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
