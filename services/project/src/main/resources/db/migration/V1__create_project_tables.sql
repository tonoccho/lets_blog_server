-- project-serviceへのスキーマ所有権移管(#577 stage 1)。sites/projects/ssh_key_pairs/
-- static_content/tag_design_settingsの5テーブルを、legacy-api(lets_blogスキーマ)の本番DBから
-- `SHOW CREATE TABLE`で取得した最終形と同一のカラム構成でlbs_projectスキーマに作成する。
-- 既存データの移行は別途行う(このマイグレーション自体は空のテーブルを作るだけで、データはコピーしない)。
--
-- content-service(#576)等の他サービスと異なり、この5テーブルは互いに実際のFOREIGN KEY関係を持つ
-- (tag_design_settings→projects、projects→sites、static_content→sites)。全て同一スキーマ
-- (lbs_project)内に収まるため、ADR-0004が禁じるクロススキーマFKには該当せず、本番DBの制約定義を
-- そのまま踏襲できる。FK依存順(sites→projects→ssh_key_pairs→static_content→tag_design_settings)
-- でCREATE TABLEする。
--
-- Stage 1時点ではJavaアプリケーションコードが実装するのはssh_key_pairs/tag_design_settingsの2ドメイン
-- のみで、sites/projects/static_contentのエンティティ・APIはまだ実装しない(後続stageの対象)。
-- DBスキーマがアプリケーションコードより先行する状態はcontent-serviceのV1でも前例がある。

CREATE TABLE `sites` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `site_key` varchar(100) NOT NULL,
  `cms_type` varchar(50) NOT NULL DEFAULT 'WORDPRESS',
  `base_url` varchar(500) NOT NULL,
  `wp_username` varchar(255) DEFAULT NULL,
  `wp_app_password_encrypted` varbinary(1024) DEFAULT NULL,
  `credentials_encrypted` varbinary(2048) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `managed_wordpress` tinyint(1) NOT NULL DEFAULT '0',
  `wp_slug` varchar(100) DEFAULT NULL,
  `wp_db_name` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `site_key` (`site_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `projects` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `slug` varchar(255) NOT NULL,
  `local_site_id` bigint DEFAULT NULL,
  `test_site_id` bigint DEFAULT NULL,
  `production_site_id` bigint DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `master_environment` varchar(20) NOT NULL DEFAULT 'test',
  `github_repository` varchar(255) DEFAULT NULL,
  `github_token_encrypted` varbinary(1024) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `slug` (`slug`),
  KEY `local_site_id` (`local_site_id`),
  KEY `test_site_id` (`test_site_id`),
  KEY `production_site_id` (`production_site_id`),
  CONSTRAINT `projects_ibfk_1` FOREIGN KEY (`local_site_id`) REFERENCES `sites` (`id`) ON DELETE SET NULL,
  CONSTRAINT `projects_ibfk_2` FOREIGN KEY (`test_site_id`) REFERENCES `sites` (`id`) ON DELETE SET NULL,
  CONSTRAINT `projects_ibfk_3` FOREIGN KEY (`production_site_id`) REFERENCES `sites` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ssh_key_pairs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `comment` varchar(255) DEFAULT NULL,
  `public_key_line` text NOT NULL,
  `private_key_encrypted` varbinary(4096) NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ssh_key_pairs_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `static_content` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `site_id` bigint NOT NULL,
  `content_type` varchar(20) NOT NULL COMMENT 'PRIVACY_POLICY or OPERATOR_INFO',
  `body` longtext NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_static_content_site_type` (`site_id`,`content_type`),
  CONSTRAINT `fk_static_content_site` FOREIGN KEY (`site_id`) REFERENCES `sites` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tag_design_settings` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `tag_type` varchar(20) NOT NULL,
  `preset_id` varchar(50) NOT NULL,
  `background_color` varchar(7) NOT NULL,
  `text_color` varchar(7) NOT NULL,
  `accent_color` varchar(7) NOT NULL,
  `custom_css` text,
  `html_template` text,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tag_design_settings_project_tag` (`project_id`,`tag_type`),
  CONSTRAINT `fk_tag_design_settings_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
