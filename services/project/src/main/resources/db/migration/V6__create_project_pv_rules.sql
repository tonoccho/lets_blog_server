-- プロジェクトの PV 達成ルールと、本番サイトのプラグインへの送信状態(issue #1578)。
-- ルールの正本はアプリ。プラグインへは「ルール全件」を丸ごと送って置き換える(`wp letsblog pv rules set`)。
-- period は daily(1日)か total(累計)。プラグインへ渡す id は "r" + この id(再送しても変わらない)。
CREATE TABLE `project_pv_rules` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `period` varchar(10) NOT NULL,
  `threshold` int NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_project_pv_rules_project` (`project_id`),
  CONSTRAINT `fk_project_pv_rules_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 最後に本番サイトへ送った結果。行が無いプロジェクトは、まだ一度も送っていない。
-- error には本番サイトの wp-cli が返した理由だけを入れる(GA4 の認証情報は入れない)。
CREATE TABLE `project_pv_sync_state` (
  `project_id` bigint NOT NULL,
  `state` varchar(10) NOT NULL,
  `error` text DEFAULT NULL,
  `sent_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`project_id`),
  CONSTRAINT `fk_project_pv_sync_state_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
