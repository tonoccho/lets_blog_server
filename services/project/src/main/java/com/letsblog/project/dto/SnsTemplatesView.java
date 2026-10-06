package com.letsblog.project.dto;

/**
 * プロジェクト設定画面の「SNS 告知」欄に出す告知文テンプレート(issue #1583)。
 *
 * @param publishTemplate 公開時のテンプレート。空は既定の告知文
 * @param pvTemplate      PV 達成時のテンプレート。空は既定の告知文
 * @param send            本番サイトのプラグインへ最後に送った結果
 */
public record SnsTemplatesView(String publishTemplate, String pvTemplate, Send send) {

    /** @param state {@code NONE}(まだ送っていない)・{@code SENT}・{@code FAILED} @param at 最後に送った時刻(UTC、ISO-8601) */
    public record Send(String state, String error, String at) {
    }
}
