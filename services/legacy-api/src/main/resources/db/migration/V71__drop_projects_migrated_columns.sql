-- projects god-tableの分割(issue #571)の仕上げ: V67〜V70で各設定テーブルへ移行済みのカラムをprojectsから
-- 削除する。projectsに残すのはプロジェクトの識別情報のみ(id/name/slug/各環境のsite_id/master_environment/
-- github_repository/github_token_encrypted)。データの移行はV67〜V70で完了しているため、このマイグレーションを
-- 単独で流す前に必ずV67〜V70が適用済み(=各設定テーブルにデータが存在する)ことを確認すること。

ALTER TABLE projects
    DROP COLUMN css_selector_prefix,
    DROP COLUMN default_negative_prompt,
    DROP COLUMN default_quality_prompt,
    DROP COLUMN default_generated_image_width,
    DROP COLUMN default_generated_image_height,
    DROP COLUMN default_article_image_long_edge_px,
    DROP COLUMN llm_model,
    DROP COLUMN llm_provider,
    DROP COLUMN comfyui_checkpoint,
    DROP COLUMN block_sexual_content,
    DROP COLUMN block_violent_content,
    DROP COLUMN block_discriminatory_content,
    DROP COLUMN image_provider,
    DROP COLUMN brave_search_api_key_encrypted,
    DROP COLUMN ga_property_id,
    DROP COLUMN ga_service_account_json_encrypted,
    DROP COLUMN adsense_account_id,
    DROP COLUMN adsense_refresh_token_encrypted,
    DROP COLUMN adsense_oauth_client_id,
    DROP COLUMN adsense_oauth_client_secret_encrypted;
