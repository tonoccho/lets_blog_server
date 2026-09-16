-- 個人設定のタイムゾーンを任意の上書きにする(issue #1259)。
--
-- ユーザー方針(2026-09-11):このシステムで内部的に使うものはすべてUTCとし、日時の表示は
-- 閲覧者のブラウザのタイムゾーンで行う。個人設定のタイムゾーンは「上書き用」として残し、
-- 設定していない人はブラウザのタイムゾーンに従う。
--
-- 従来の users.timezone は VARCHAR(50) DEFAULT 'Asia/Tokyo' で、全ユーザーが常に値を持っていた
-- (V1__create_identity_tables.sql:42)。既定値を撤去し、NULL(未設定)を許すようにする。
-- timezone列にNOT NULL制約はもともと無いため、DROP DEFAULTとUPDATEだけで足りる。
--
-- 注意: DBのDEFAULT句はJPAのINSERT(全カラムを明示する)には効かない。新規作成時の
-- 実質的なデフォルト値はUser.javaのフィールド初期値であり、そちらも本issueで撤去済み
-- (services/identity/src/main/java/com/letsblog/identity/domain/User.java)。

ALTER TABLE users ALTER COLUMN timezone DROP DEFAULT;

-- 既存ユーザーのうち、既定値'Asia/Tokyo'のままだった行だけをNULLへ戻す。既定値と同じ値を
-- 意図して設定したユーザーと、既定値のままのユーザーはDB上で区別できないため、
-- ユーザー方針に従い一律にNULLへ戻す。他の値(例: 'America/New_York')の行は変更しない。
UPDATE users SET timezone = NULL WHERE timezone = 'Asia/Tokyo';
