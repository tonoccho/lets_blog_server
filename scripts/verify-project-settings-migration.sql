-- projects god-table分割(issue #571)の移行検証スクリプト。
--
-- 目的: V67(project_ai_settings)〜V70(project_content_settings)で新設定テーブルへコピーした値が、
-- projectsの移行元カラムと完全に一致していることを確認する。暗号化カラム(*_encrypted)はバイト列を
-- そのままコピーしているだけなので、この比較が一致していればAPP_ENCRYPTION_KEYでの復号可否にも影響しない。
--
-- 実行タイミング: V67〜V70が適用済みで、かつV71(projectsから移行済みカラムを削除)がまだ適用されていない
-- 状態で実行すること(V71適用後はprojects側の比較元カラムが存在しないため、このスクリプトは実行できない)。
-- 本番カットオーバー時は、V67〜V70をデプロイして検証がすべてOKであることを確認したうえでV71を含む
-- 次のデプロイに進む、という2段階の運用を想定している。
--
-- 使い方:
--   mysql -h <host> -u <user> -p <database> < scripts/verify-project-settings-migration.sql
--
-- 各SELECTが返す行が1件も無ければ、そのテーブルの移行は不整合が無いことを意味する
-- (不一致があれば、不一致のprojects.idと新旧の値が1行ずつ出力される)。

-- ---- project_ai_settings ----
SELECT 'project_ai_settings' AS table_name, p.id AS project_id,
       p.llm_model AS old_llm_model, s.llm_model AS new_llm_model,
       p.llm_provider AS old_llm_provider, s.llm_provider AS new_llm_provider,
       (p.brave_search_api_key_encrypted <=> s.brave_search_api_key_encrypted) AS brave_key_matches
FROM projects p
LEFT JOIN project_ai_settings s ON s.project_id = p.id
WHERE NOT (p.llm_model <=> s.llm_model)
   OR NOT (p.llm_provider <=> s.llm_provider)
   OR NOT (p.brave_search_api_key_encrypted <=> s.brave_search_api_key_encrypted);

-- ---- project_image_settings ----
SELECT 'project_image_settings' AS table_name, p.id AS project_id
FROM projects p
LEFT JOIN project_image_settings s ON s.project_id = p.id
WHERE NOT (p.image_provider <=> s.image_provider)
   OR NOT (p.comfyui_checkpoint <=> s.comfyui_checkpoint)
   OR NOT (p.default_negative_prompt <=> s.default_negative_prompt)
   OR NOT (p.default_quality_prompt <=> s.default_quality_prompt)
   OR NOT (p.default_generated_image_width <=> s.default_generated_image_width)
   OR NOT (p.default_generated_image_height <=> s.default_generated_image_height)
   OR NOT (p.default_article_image_long_edge_px <=> s.default_article_image_long_edge_px)
   OR NOT (p.block_sexual_content <=> s.block_sexual_content)
   OR NOT (p.block_violent_content <=> s.block_violent_content)
   OR NOT (p.block_discriminatory_content <=> s.block_discriminatory_content);

-- ---- analytics_credentials ----
SELECT 'analytics_credentials' AS table_name, p.id AS project_id
FROM projects p
LEFT JOIN analytics_credentials s ON s.project_id = p.id
WHERE NOT (p.ga_property_id <=> s.ga_property_id)
   OR NOT (p.ga_service_account_json_encrypted <=> s.ga_service_account_json_encrypted)
   OR NOT (p.adsense_account_id <=> s.adsense_account_id)
   OR NOT (p.adsense_refresh_token_encrypted <=> s.adsense_refresh_token_encrypted)
   OR NOT (p.adsense_oauth_client_id <=> s.adsense_oauth_client_id)
   OR NOT (p.adsense_oauth_client_secret_encrypted <=> s.adsense_oauth_client_secret_encrypted);

-- ---- project_content_settings ----
SELECT 'project_content_settings' AS table_name, p.id AS project_id
FROM projects p
LEFT JOIN project_content_settings s ON s.project_id = p.id
WHERE NOT (p.css_selector_prefix <=> s.css_selector_prefix);

-- ---- 参考: 新テーブルのうち、どのprojectsにも対応しない孤児行が無いことも確認する ----
SELECT 'orphaned_project_ai_settings' AS check_name, s.id, s.project_id
FROM project_ai_settings s LEFT JOIN projects p ON p.id = s.project_id WHERE p.id IS NULL
UNION ALL
SELECT 'orphaned_project_image_settings', s.id, s.project_id
FROM project_image_settings s LEFT JOIN projects p ON p.id = s.project_id WHERE p.id IS NULL
UNION ALL
SELECT 'orphaned_analytics_credentials', s.id, s.project_id
FROM analytics_credentials s LEFT JOIN projects p ON p.id = s.project_id WHERE p.id IS NULL
UNION ALL
SELECT 'orphaned_project_content_settings', s.id, s.project_id
FROM project_content_settings s LEFT JOIN projects p ON p.id = s.project_id WHERE p.id IS NULL;
