-- WordPress の letsblog プラグインへのタグ・CSS・プレフィックス・デザインの同期の状態(issue #1558)。
-- status が NULL のサイトは、まだ一度も同期していない。
ALTER TABLE sites
    ADD COLUMN letsblog_sync_status VARCHAR(20) NULL,
    ADD COLUMN letsblog_sync_error TEXT NULL,
    ADD COLUMN letsblog_sync_hash VARCHAR(64) NULL,
    ADD COLUMN letsblog_synced_at TIMESTAMP NULL;
