package com.letsblog.publishing.cms;

import java.time.Instant;
import java.util.List;

public record PostContent(
        String title,
        String slug,
        String htmlContent,
        String status,
        List<String> categoryIds,
        List<String> tagIds,
        String featuredMediaId,
        String authorId,
        /**
         * 公開予定日時(UTC)。指定がある場合、statusは"future"となり、CMS側で
         * この日時に自動公開される。予約投稿を使わない場合はnull。
         */
        Instant publishScheduledAt
) {
    /** 公開予定日時を持たない投稿を作る(既存の呼び出し互換用)。 */
    public PostContent(
            String title, String slug, String htmlContent, String status,
            List<String> categoryIds, List<String> tagIds, String featuredMediaId, String authorId) {
        this(title, slug, htmlContent, status, categoryIds, tagIds, featuredMediaId, authorId, null);
    }
}
