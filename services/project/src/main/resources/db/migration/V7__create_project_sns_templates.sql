-- プロジェクトの告知文テンプレートと、本番サイトのプラグインへの送信状態(issue #1583)。
-- テンプレートの正本はアプリ。プラグインへは公開時(publish)と PV 達成時(pv)をまとめて丸ごと送って置き換える
-- (`wp letsblog sns templates set`)。空文字は「既定の告知文を使う」の意味。行が無いプロジェクトは、まだ一度も保存していない。
-- sync_state は SENT か FAILED(NULL はまだ送っていない)。sync_error には本番サイトの wp-cli が返した理由だけを入れる。
CREATE TABLE `project_sns_templates` (
  `project_id` bigint NOT NULL,
  `publish_template` varchar(1000) NOT NULL DEFAULT '',
  `pv_template` varchar(1000) NOT NULL DEFAULT '',
  `sync_state` varchar(10) DEFAULT NULL,
  `sync_error` text DEFAULT NULL,
  `sent_at` datetime DEFAULT NULL,
  PRIMARY KEY (`project_id`),
  CONSTRAINT `fk_project_sns_templates_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
