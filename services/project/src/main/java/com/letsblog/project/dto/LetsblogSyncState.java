package com.letsblog.project.dto;

import com.letsblog.project.domain.LetsblogSyncStatus;
import com.letsblog.project.domain.Site;
import java.time.Instant;

/**
 * サイトの letsblog プラグインへの同期の状態(issue #1558)。サイト画面で、失敗したサイトを見分けたり、
 * 同期済みのハッシュを確認したりするために返す。
 *
 * @param status   同期の結果
 * @param error    失敗・見送りの理由。同期済みならnull
 * @param hash     同期済みの内容のハッシュ。同期済み以外はnull
 * @param syncedAt 最後に同期を試みた日時
 */
public record LetsblogSyncState(LetsblogSyncStatus status, String error, String hash, Instant syncedAt) {

    /** まだ一度も同期していないサイトはnull。 */
    public static LetsblogSyncState from(Site site) {
        if (site.getLetsblogSyncStatus() == null) {
            return null;
        }
        return new LetsblogSyncState(
                site.getLetsblogSyncStatus(),
                site.getLetsblogSyncError(),
                site.getLetsblogSyncHash(),
                UtcDateTimes.toInstant(site.getLetsblogSyncedAt()));
    }
}
