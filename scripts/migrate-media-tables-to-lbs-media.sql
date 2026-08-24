-- media-serviceへのスキーマ所有権移管(issue #573)データ移行スクリプト。
--
-- 目的: generated_images/generated_image_sequences/diagrams/project_image_settingsの4テーブルを、
-- legacy-apiが所有するlets_blogスキーマから、media-serviceが新たに所有するlbs_mediaスキーマへ
-- コピーする。lbs_mediaスキーマの専用DBユーザー(ADR-0004)はlets_blogスキーマへの権限を持たず、
-- 逆にlets_blogのDBユーザーもlbs_mediaへの権限を持たないため、通常のアプリケーションレベルの
-- FlywayマイグレーションではこのクロススキーマのINSERT ... SELECTを実行できない。両スキーマへ
-- アクセスできる特権アカウント(root)で本スクリプトを直接実行する運用とする
-- (#572のmigrate-log-tables-to-lbs-log.sqlと同じパターン)。
--
-- 前提条件(必ずこの順序で行うこと):
--   1. media-serviceのFlyway V1(services/media/src/main/resources/db/migration/
--      V1__create_media_tables.sql)を適用済みで、lbs_mediaスキーマに空の4テーブルが存在すること。
--   2. legacy-api側でこれらのテーブルを削除するマイグレーションは(#573のstage1時点では)まだ存在しない。
--      generated_images/generated_image_sequences/diagramsはstage1でlegacy-api側のコード参照が
--      無くなるため後日削除マイグレーションを追加できるが、project_image_settingsはstage2
--      (ImageModelService/ComfyUiModelServiceの移設)までlegacy-api側のコードが引き続き参照するため、
--      その削除マイグレーションはstage2で追加する。
--
-- 実行方法(root権限が必要。スキーマ名はデフォルト値。実際の値は.envのMYSQL_DATABASEを確認して
-- 必要に応じて置き換えること):
--   docker exec -i lbs-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < scripts/migrate-media-tables-to-lbs-media.sql
--
-- 実行後、最後のSELECTでlets_blog側とlbs_media側の件数が一致していることを確認すること。
--
-- 冪等性: 対象4テーブルはmedia-serviceのV1で作成された直後の空テーブルであることを前提とする
-- (2回目以降の実行はauto_incrementの主キー衝突/UNIQUE制約違反でエラーになる。再実行が必要な場合は
-- 事前にlbs_media側の4テーブルをTRUNCATEすること)。
--
-- 注意: project_image_settingsは、stage2でlegacy-api側の削除マイグレーションを適用するまでの間、
-- 両スキーマに同一データのコピーが存在する状態になる(legacy-api側は引き続きImageModelService/
-- ComfyUiModelServiceが読み書きするため、このコピー以降に加えられた変更はlbs_media側には
-- 反映されない)。stage2でlegacy-api側の参照を完全にmedia-serviceへ委譲するタイミングで、
-- 本スクリプトのproject_image_settings部分を(TRUNCATEしてから)再実行し、最新の状態を
-- 取り込み直してからlegacy-api側の削除マイグレーションを適用すること。

INSERT INTO lbs_media.generated_images
    (id, project_id, prompt, negative_prompt, steps, cfg_scale, sampler_name, scheduler, seed, width, height,
     batch_size, checkpoint, lora_name, lora_weight, file_path, mime_type, provider, tags_json, created_at)
SELECT
    id, project_id, prompt, negative_prompt, steps, cfg_scale, sampler_name, scheduler, seed, width, height,
    batch_size, checkpoint, lora_name, lora_weight, file_path, mime_type, provider, tags_json, created_at
FROM lets_blog.generated_images;

INSERT INTO lbs_media.generated_image_sequences (project_key, last_seq, updated_at)
SELECT project_key, last_seq, updated_at
FROM lets_blog.generated_image_sequences;

INSERT INTO lbs_media.diagrams (id, project_id, name, xml, svg, created_at, updated_at)
SELECT id, project_id, name, xml, svg, created_at, updated_at
FROM lets_blog.diagrams;

INSERT INTO lbs_media.project_image_settings
    (id, project_id, image_provider, comfyui_checkpoint, default_negative_prompt, default_quality_prompt,
     default_generated_image_width, default_generated_image_height, default_article_image_long_edge_px,
     block_sexual_content, block_violent_content, block_discriminatory_content, created_at, updated_at)
SELECT
    id, project_id, image_provider, comfyui_checkpoint, default_negative_prompt, default_quality_prompt,
    default_generated_image_width, default_generated_image_height, default_article_image_long_edge_px,
    block_sexual_content, block_violent_content, block_discriminatory_content, created_at, updated_at
FROM lets_blog.project_image_settings;

-- コピー後のAUTO_INCREMENTを、コピーしたidの最大値+1から再開するよう調整する
-- (そうしないと次回INSERT時に既存の(コピー済み)idと衝突する)。generated_image_sequencesは
-- project_keyが主キー(AUTO_INCREMENTなし)のため対象外。
SET @gi_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_media.generated_images);
SET @sql = CONCAT('ALTER TABLE lbs_media.generated_images AUTO_INCREMENT = ', @gi_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @dg_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_media.diagrams);
SET @sql = CONCAT('ALTER TABLE lbs_media.diagrams AUTO_INCREMENT = ', @dg_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @pis_next = (SELECT COALESCE(MAX(id), 0) + 1 FROM lbs_media.project_image_settings);
SET @sql = CONCAT('ALTER TABLE lbs_media.project_image_settings AUTO_INCREMENT = ', @pis_next);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 件数突合(source/destが一致していれば移行成功)。
SELECT 'generated_images' AS table_name,
       (SELECT COUNT(*) FROM lets_blog.generated_images) AS source_count,
       (SELECT COUNT(*) FROM lbs_media.generated_images) AS dest_count
UNION ALL
SELECT 'generated_image_sequences',
       (SELECT COUNT(*) FROM lets_blog.generated_image_sequences),
       (SELECT COUNT(*) FROM lbs_media.generated_image_sequences)
UNION ALL
SELECT 'diagrams',
       (SELECT COUNT(*) FROM lets_blog.diagrams),
       (SELECT COUNT(*) FROM lbs_media.diagrams)
UNION ALL
SELECT 'project_image_settings',
       (SELECT COUNT(*) FROM lets_blog.project_image_settings),
       (SELECT COUNT(*) FROM lbs_media.project_image_settings);
