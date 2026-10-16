-- プロジェクト単位のプラグインの登録の上書き(issue #1808、docs/plugins/CONTRACTS.md §4.2)。
-- システム全体の登録(plugin_registrations)とは別のテーブルにする: 解決順がプロジェクト → システム全体 → 未接続で、
-- 上書きした種類は上書き側だけを見る(混ぜない)ため、同じ表に混ぜると全件を引く既存の問い合わせが上書きを拾ってしまう。
-- Penpot は上書きの対象にしない(接続先はシステム全体に1つ)ので、アプリケーション側で kind を LLM / IMAGE_GENERATION /
-- DIAGRAM_RENDERER に限る。認証情報は CredentialCipher (AES-256-GCM) で暗号化した BLOB で、画面・取得 API には値を出さない。
-- project_id は project-service が所有する projects の ID で、スキーマが別(ADR-0004)なので外部キーは張らない。
CREATE TABLE project_plugin_registrations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    kind VARCHAR(30) NOT NULL,
    name VARCHAR(100) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    in_use BOOLEAN NOT NULL DEFAULT FALSE,
    drawio_url VARCHAR(2048) NULL,
    credential_encrypted BLOB NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_project_plugin_registrations_project_kind (project_id, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
