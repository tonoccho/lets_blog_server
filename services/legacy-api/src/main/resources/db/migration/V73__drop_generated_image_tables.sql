-- media-serviceへ生成画像の所有権を完全移管する(#573)。generated_images/generated_image_sequences
-- の2テーブルをlets_blogスキーマから削除する。
--
-- 重要: このマイグレーションを環境へ適用する前に、必ず以下の順序を守ること。
--   1. media-serviceのFlyway V1(services/media/src/main/resources/db/migration/
--      V1__create_media_tables.sql、issue #573 stage1で適用済みのはず)がlbs_mediaスキーマに
--      適用されていること。
--   2. scripts/migrate-media-tables-to-lbs-media.sql(#573 stage1で実行済みのはず)で
--      既存データがlets_blog(このスキーマ)からlbs_mediaへコピー済みであること。
--   3. #573 stage1〜stage3で、generated_images/generated_image_sequencesへの参照がlegacy-api側に
--      一切残っていないこと(GeneratedImageController/DiagramController等はmedia-serviceへ
--      移設済み)。stage4で、legacy-api側の最後の参照元だったAiAssistService#generateImage
--      (実際の画像生成AI呼び出しは引き続きlegacy-apiが行うが、保存はMediaGeneratedImageClient
--      経由でmedia-serviceへ委譲するよう書き換えた)とProjectController#uploadAssetImage
--      (画像取得もMediaGeneratedImageClient経由に書き換えた)が最後の参照元だった。
--   4. 上記が確認できてから、legacy-apiを再ビルド/再起動してこのV73を適用する。
--
-- diagrams/project_image_settingsは対象外。diagramsはstage1で既にlegacy-api側の参照が
-- 無くなっていたが未削除だったため、本マイグレーションと合わせて削除する。
-- project_image_settingsはImageModelService/ComfyUiModelService(いずれもlegacy-apiに残る、
-- PR説明参照)が引き続き参照するため対象外のまま。

DROP TABLE IF EXISTS generated_images;
DROP TABLE IF EXISTS generated_image_sequences;
DROP TABLE IF EXISTS diagrams;
