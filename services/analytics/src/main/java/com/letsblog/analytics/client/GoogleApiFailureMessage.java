package com.letsblog.analytics.client;

import org.springframework.http.HttpStatusCode;

/**
 * Google の API(GA4 Data API / AdSense Management API / OAuth トークンエンドポイント)が
 * 返したエラーを、<b>利用者がそのまま読める説明</b>へ変換する(issue #939 / AT-13)。
 *
 * <p>ここで作った文字列は握り潰されない。{@code GoogleAnalyticsReportService} /
 * {@code AdSenseReportService} が {@code errorMessage} に載せ、プロジェクトダッシュボードの
 * ウィジェットが「取得に失敗しました: …」としてそのまま表示する。つまりこれは
 * <b>画面の文言</b>であって、ログの文言ではない。
 *
 * <p>#939 以前は全ての失敗が「&lt;操作&gt;に失敗しました: &lt;ステータス&gt; &lt;Googleの応答本文&gt;」
 * という1つの形だった。2つの問題があった。
 *
 * <ul>
 *   <li><b>次に何をすればよいか分からない。</b> 資格情報が失効した(401)ときの表示が
 *       「Google OAuth認証に失敗しました: 401 UNAUTHORIZED {…}」で、
 *       <b>再認証すれば直る</b>ことがどこにも書かれていなかった(#939 受け入れ基準10)。</li>
 *   <li><b>レート制限(429)が「認証に失敗」と読める。</b> 待てばよいだけの状態で
 *       資格情報を疑わせるのは誤った誘導である(#939 受け入れ基準11)。</li>
 * </ul>
 *
 * <p>この2つだけを個別の文言にし、<b>それ以外のステータスは従来どおりの形のまま</b>にしてある。
 * 他のステータス(403 の権限不足など)にどう案内するかは #939 の受け入れ基準の範囲外で、
 * ここで一緒に変えると受け入れテストの裏付けの無い変更になる。
 */
public final class GoogleApiFailureMessage {

    private GoogleApiFailureMessage() {
    }

    /**
     * @param what   何をしようとしたか。従来の文言の主語部分をそのまま渡す
     *               (例: {@code "Google OAuth認証"}、{@code "AdSense Management APIの呼び出し"})
     * @param status Google が返したHTTPステータス
     * @param responseBody Google が返した応答本文。401 / 429 では<b>利用者へ出さない</b>
     */
    public static String of(String what, HttpStatusCode status, String responseBody) {
        if (status.value() == 401) {
            return what + "がGoogleに拒否されました(401)。資格情報が失効している可能性があります。"
                    + "設定画面から再認証してください。";
        }
        if (status.value() == 429) {
            return what + "がGoogleの呼び出し回数制限に達しました(429)。"
                    + "しばらく待ってから再度お試しください。";
        }
        return what + "に失敗しました: " + status + " " + responseBody;
    }
}
