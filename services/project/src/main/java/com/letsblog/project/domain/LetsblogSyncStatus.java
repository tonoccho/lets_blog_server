package com.letsblog.project.domain;

/** サイトの letsblog プラグインへの同期の結果(issue #1558)。 */
public enum LetsblogSyncStatus {
    /** 送信でき、プラグインが保存したハッシュがアプリ側のハッシュと一致した。 */
    SYNCED,
    /** 届かなかった(状態の取得・送信の失敗、ハッシュの不一致)。再同期で回復できる。失敗として画面に出す。 */
    FAILED,
    /** プラグインが未導入・要更新(#1557)、またはプロジェクトに紐付いていないため、送らなかった。 */
    SKIPPED
}
