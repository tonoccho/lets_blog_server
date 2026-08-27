-- mail_templatesの撤去(issue #680)。唯一の利用者だったMailSenderServiceがissue #566で
-- 削除されて以降、mail_templatesは参照元を持たない孤立テーブルとなっていたため撤去する。
DROP TABLE IF EXISTS mail_templates;
