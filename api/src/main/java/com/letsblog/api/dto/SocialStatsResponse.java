package com.letsblog.api.dto;

/**
 * プロジェクトダッシュボードのソーシャル統計ウィジェット向けレスポンス(issue #390)。
 * GA/AdSenseと異なり期間ではなく「Buffer経由で送信済みの投稿N件を対象に集計した」ことを示す
 * postCountを返す。eligible=falseはBuffer連携が無効、または対象の送信済み投稿が無いことを表す。
 */
public record SocialStatsResponse(
        boolean eligible,
        Integer postCount,
        Long likes,
        Long shares,
        Long comments,
        Long clicks,
        String errorMessage
) {
    public static SocialStatsResponse notEligible() {
        return new SocialStatsResponse(false, null, null, null, null, null, null);
    }

    public static SocialStatsResponse of(int postCount, long likes, long shares, long comments, long clicks) {
        return new SocialStatsResponse(true, postCount, likes, shares, comments, clicks, null);
    }

    public static SocialStatsResponse error(String errorMessage) {
        return new SocialStatsResponse(true, null, null, null, null, null, errorMessage);
    }
}
