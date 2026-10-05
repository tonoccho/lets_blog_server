package com.letsblog.project.dto;

import java.util.List;

/**
 * プロジェクト設定画面の「SNS 告知」欄に出す PV 達成ルール(issue #1578)。GA4 の認証情報は含まない。
 *
 * @param addable ルールを追加できる(GA が連携済み)
 * @param reason  追加できない理由。追加できるなら null
 * @param rules   ルール(追加の古い順)
 * @param send    本番サイトのプラグインへ最後に送った結果
 */
public record PvRulesView(boolean addable, String reason, List<Rule> rules, Send send) {

    /** @param id プラグインへ渡す ID({@code r<番号>}) @param period {@code daily}(1日)か {@code total}(累計) */
    public record Rule(String id, String period, int threshold) {
    }

    /** @param state {@code NONE}(まだ送っていない)・{@code SENT}・{@code FAILED} @param at 最後に送った時刻(UTC、ISO-8601) */
    public record Send(String state, String error, String at) {
    }
}
