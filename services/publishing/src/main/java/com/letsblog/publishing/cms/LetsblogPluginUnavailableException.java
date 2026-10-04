package com.letsblog.publishing.cms;

/**
 * letsblog プラグインが使えない(未導入・要更新の)サイトへ、投稿やプレビューをしようとしたときに投げる
 * (issue #1557)。{@link IllegalStateException}なので、{@code GlobalExceptionHandler}が409で返す。
 * 利用者が次に何をすればよいか(サイト画面からの再導入)をメッセージに含める。
 */
public class LetsblogPluginUnavailableException extends IllegalStateException {

    private final LetsblogPluginStatus status;

    public LetsblogPluginUnavailableException(LetsblogPluginStatus status) {
        super("このサイトでは letsblog プラグインが使えない(" + label(status) + ")ため、投稿とプレビューはできません。"
                + "サイト画面の「プラグインを再導入」を実行してください。");
        this.status = status;
    }

    public LetsblogPluginStatus getStatus() {
        return status;
    }

    private static String label(LetsblogPluginStatus status) {
        return status.state() == LetsblogPluginStatus.State.NEEDS_UPDATE ? "要更新" : "未導入";
    }
}
