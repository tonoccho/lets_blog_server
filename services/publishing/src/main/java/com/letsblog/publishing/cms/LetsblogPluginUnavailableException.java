package com.letsblog.publishing.cms;

/**
 * letsblog プラグインが使えない(未導入・要更新の)サイトへ、投稿やプレビューをしようとしたときに投げる
 * (issue #1557)。{@link IllegalStateException}なので、{@code GlobalExceptionHandler}が409で返す。
 * 利用者が次に何をすればよいか(サイト画面からの再導入)をメッセージに含める。
 */
public class LetsblogPluginUnavailableException extends IllegalStateException {

    public static final String CODE_NOT_INSTALLED = "LETSBLOG_PLUGIN_NOT_INSTALLED";
    public static final String CODE_NEEDS_UPDATE = "LETSBLOG_PLUGIN_NEEDS_UPDATE";

    private final LetsblogPluginStatus status;

    public LetsblogPluginUnavailableException(LetsblogPluginStatus status) {
        super("このサイトでは letsblog プラグインが使えない(" + label(status) + ")ため、投稿とプレビューはできません。"
                + "サイト画面の「プラグインを再導入」を実行してください。");
        this.status = status;
    }

    public LetsblogPluginStatus getStatus() {
        return status;
    }

    /** 409の応答に載せる機械可読なコード(issue #1619)。クライアントは文言でなくこれで判定する。 */
    public String getCode() {
        return status.state() == LetsblogPluginStatus.State.NEEDS_UPDATE
                ? CODE_NEEDS_UPDATE : CODE_NOT_INSTALLED;
    }

    private static String label(LetsblogPluginStatus status) {
        return status.state() == LetsblogPluginStatus.State.NEEDS_UPDATE ? "要更新" : "未導入";
    }
}
