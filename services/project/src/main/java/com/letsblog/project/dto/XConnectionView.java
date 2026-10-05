package com.letsblog.project.dto;

import java.util.List;

/**
 * プロジェクト設定画面の「SNS 告知」欄に出す X の接続状態(issue #1574)。トークンもクライアントの秘密も含まない
 * (本番サイトのプラグインが暗号化して持ち、アプリは受け取らない)。
 *
 * <p>本番サイトが無い・プラグインが導入済みでないときは {@code connectable=false} と理由 {@code reason}、
 * 状態と履歴は null。本番サイトに届かないときは {@link Status#available()} / {@link Log#available()} が false で、
 * 画面は「取得できない」と示す。
 *
 * @param connectable 接続操作ができる(本番サイトがあり、プラグインが導入済み)
 * @param reason      接続できない理由。接続できるなら null
 * @param siteName    本番サイトの名前。無ければ null
 */
public record XConnectionView(boolean connectable, String reason, String siteName, Status status, Log log) {

    public enum State {
        /** 未設定(=未接続)。 */
        UNSET,
        CONNECTED,
        /** 要再接続(トークンが失効した等)。 */
        RECONNECT
    }

    /** 接続状態。取得できなかったときは {@code available=false}、{@code state} は null。 */
    public record Status(boolean available, State state, String accountName, String error) {
        public static Status unavailable(String error) {
            return new Status(false, null, null, error);
        }
    }

    /** 告知履歴(新しい順)。取得できなかったときは {@code available=false}。 */
    public record Log(boolean available, List<Entry> entries, String error) {
        public static Log unavailable(String error) {
            return new Log(false, List.of(), error);
        }
    }

    /** 告知履歴の1件。{@code kind} は test / publish など、プラグインが記録した種類。 */
    public record Entry(String kind, boolean success, String error, String at) {
    }
}
