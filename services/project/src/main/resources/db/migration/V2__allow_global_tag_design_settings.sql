-- issue #763: tag_design_settings に project_id IS NULL の「グローバル既定」行を持てるようにする。
--
-- 背景(#760): プロジェクトに紐付いていないサイトへ公開すると projectId=null で解決要求が来る。
-- そのとき返すデザインを「運用者が設定できるグローバル既定行」にしたかったが、
-- project_id が NOT NULL + projects(id) への FK だったため IS NULL の行を持てず、
-- 変更できないハードコード既定(DesignPreset.DEFAULT の色 / テンプレート無し)を返す暫定対応になっていた。
-- content-service の custom_tags は project_id が nullable で findByProjectIdIsNull() による
-- グローバル行運用ができており、2つのテーブルで設計方針が揃っていなかった。
--
-- FK は残す。MySQL では NULL の外部キー値は参照整合性違反にならないため、
-- nullable 化と ON DELETE CASCADE は両立する(プロジェクト削除時に紐付き行だけが消える挙動は変わらない)。
--
-- 一意性について: MySQL の UNIQUE は NULL を互いに異なる値として扱うため、
-- UNIQUE (project_id, tag_type) のままでは (NULL, 'TOC') を何行でも作れてしまう。
-- グローバル行はタグ種別ごとに1行でなければ findByProjectIdIsNullAndTagType が
-- IncorrectResultSizeDataAccessException で落ちる。アプリ側の upsert は
-- 「引いて無ければ作る」なので、同時実行で二重作成されうる。
-- そこで NULL を実在しない ID (-1) へ畳んだ生成カラムを一意性の担保に使う。
-- projects.id は AUTO_INCREMENT で採番されるため実運用で -1 になることはない。
-- (AUTO_INCREMENT は明示的な負値の INSERT を禁止しないので DB 制約としての保証ではないが、
--  Project エンティティは GenerationType.IDENTITY で、アプリからIDを明示指定する経路が無い)
-- (custom_tags は素の UNIQUE のままで同じ穴を持つが、そちらの是正はこの Issue のスコープ外)

-- FK を張ったまま MODIFY COLUMN すると InnoDB が制約を作り直そうとして
-- "Cannot add foreign key constraint" で失敗する。明示的に外して張り直す。
--
-- FK は索引を必要とする。先に project_id 単独の索引を作っておかないと、
-- uq_tag_design_settings_project_tag(最左プレフィックスとして索引を兼ねていた)を
-- 落とした時点で FK が使える索引が無くなる。
ALTER TABLE `tag_design_settings`
  ADD INDEX `idx_tag_design_settings_project_id` (`project_id`);

ALTER TABLE `tag_design_settings`
  DROP FOREIGN KEY `fk_tag_design_settings_project`;

ALTER TABLE `tag_design_settings`
  DROP INDEX `uq_tag_design_settings_project_tag`;

ALTER TABLE `tag_design_settings`
  MODIFY COLUMN `project_id` bigint NULL;

ALTER TABLE `tag_design_settings`
  ADD CONSTRAINT `fk_tag_design_settings_project`
    FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE CASCADE;

-- VIRTUAL であること。STORED はテーブル再構築(COPYアルゴリズム)を伴い、FK を持つこの表では
-- 再構築中の制約再作成に失敗して ERROR 1215 "Cannot add foreign key constraint" になる
-- (生成カラムの追加なのにFKのエラーが出るため紛らわしい)。VIRTUAL なら再構築されない。
-- VIRTUAL 生成カラムへの UNIQUE セカンダリインデックスは MySQL 8.0 で作成できる。
ALTER TABLE `tag_design_settings`
  ADD COLUMN `project_scope` bigint
    GENERATED ALWAYS AS (IFNULL(`project_id`, -1)) VIRTUAL NOT NULL;

ALTER TABLE `tag_design_settings`
  ADD UNIQUE KEY `uq_tag_design_settings_scope_tag` (`project_scope`, `tag_type`);
