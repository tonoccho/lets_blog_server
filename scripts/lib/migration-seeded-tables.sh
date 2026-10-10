# ゼロ構築の検証(rebuild-acceptance-env.sh / reset-acceptance-env.sh)が、
# 「マイグレーション(Flyway)が行を入れる表」を残骸と取り違えないための単一の定義(#1738)。
# `source` して使う。両スクリプトはこの一覧を重複して持たない。
#
# 書式は `表名:許す最大行数`。`*` は件数を問わない(マスタデータで、増減しうる)。
# マイグレーションが `INSERT [IGNORE] INTO <表>` する表が増えたら、ここへ足すこと。
# 足し忘れは scripts/test_migration_seeded_tables.py が、リリース検証より前に失敗させる。
#
#   roles / role_permissions  V2 が入れる RBAC の定義(#956)。空のほうが異常。件数は問わない。
#   user_roles                V2 が既存ユーザーから割り当てる(INSERT … SELECT)。新規環境では 0 行。
#   initial_setup_lock        V4 が常に 1 行入れる(#1718)。1 行を超えたら残骸として NG にする。
#
# 対象外にするのは「マイグレーションが入れた行」だけ。`*` でない表は、入れた件数を超える行を
# 数える。`*` の表は件数を確かめない: マスタデータは件数が移行ごとに変わり、ここに数を書くと
# 移行のたびに検証が壊れるため。
MIGRATION_SEEDED_TABLES=(
  roles:'*'
  role_permissions:'*'
  user_roles:0
  initial_setup_lock:1
)

# 表 $1 が許す最大行数。一覧に無ければ 0(1 行でもあれば残骸)。
migration_seeded_max_rows() {
  local entry
  for entry in "${MIGRATION_SEEDED_TABLES[@]}"; do
    if [ "${entry%%:*}" = "$1" ]; then
      printf '%s\n' "${entry#*:}"
      return 0
    fi
  done
  printf '0\n'
}

# information_schema の `table_name NOT IN (...)` に入れる、数えない表の SQL リスト。
# Flyway の管理表と、件数を問わない(`*`)表。
migration_seeded_not_in_sql() {
  local list="'flyway_schema_history'" entry
  for entry in "${MIGRATION_SEEDED_TABLES[@]}"; do
    [ "${entry#*:}" = "*" ] && list="${list}, '${entry%%:*}'"
  done
  printf '%s\n' "$list"
}
