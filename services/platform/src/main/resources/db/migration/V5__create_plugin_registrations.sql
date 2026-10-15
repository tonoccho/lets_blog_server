-- システム全体のプラグインの登録(issue #1807、ADR-0013、docs/plugins/CONTRACTS.md §4.2)。
-- 種類(LLM / 画像生成 / 図のレンダリング / Penpot)ごとに接続先を持つ。複数行なので system_settings とは別のテーブルにする。
-- システム全体の登録は認証情報を持たない(§4.2)ので、暗号化する列は無い。
-- 件数の規則(図のレンダリング・Penpotは1件まで、使用中は種類ごとに1件)はアプリケーション側で保つ。
CREATE TABLE plugin_registrations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kind VARCHAR(30) NOT NULL,
    name VARCHAR(100) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    in_use BOOLEAN NOT NULL DEFAULT FALSE,
    drawio_url VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_plugin_registrations_kind (kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
