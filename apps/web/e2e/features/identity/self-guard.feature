# language: ja
# @mode:serial — どちらのシナリオも検証用アカウントへのログイン準備でKeycloakコンテナ内の
# kcadm.sh(共有の設定ファイル/opt/keycloak/.keycloak/kcadm.config)を叩く。並列に走らせると
# 設定ファイルのロック取得で衝突し、意図しない理由で失敗する(実測)。
@identity @api @mode:serial
機能: 自己権限昇格・自己締め出しの防止

  「権限を持つ利用者でも、自分自身を特別扱いして権限を広げたり、自分を締め出したりできない」
  ことを利用者視点で固定する(issue #1161。親issue #930([AT-4])の17シナリオのうち8・9を
  引き取る子issue、2026-09-08の分割)。

  #798では、ROLE_MANAGE保有者(非admin)が自分自身に特権ロール(ROLE_ADMIN)を付与できてしまう
  経路と、管理者が自分自身を無効化してロックアウトされる経路が塞がれていなかった。現行実装の
  ガードは`services/identity/.../UserController#authorizeRoleChange`(特権ロールの付与・剥奪は
  admin限定にする)と`AdminAuthorizationService#requireAdminAndNotSelf`(自己無効化の禁止)で
  ある。単体テスト(`SelfPrivilegeEscalationIntegrationTest`)では既に固定されているが、
  受け入れレベルの退行検知が無かった。

  検証用アカウントは毎回一意なメール(タイムスタンプ+乱数)で作る使い捨てであり、共有のE2E
  固定アカウント(e2e-*@letsblog.local)は対象にしない。

  シナリオ8の検証用アカウントの作り方について: 既定シードでROLE_MANAGE権限を持つロールは
  ROLE_ADMINのみである(`V2__seed_roles_and_permissions.sql`)。そのため「ROLE_MANAGE保有者
  (非admin)」を作るには、管理者が使い捨てユーザー(users.role=user)にROLE_ADMINを付与する
  ——というAPI越しに再現できる唯一の経路を使う。この使い捨てユーザーはusers.roleが"admin"に
  なることはない(ロール付与エンドポイントはRBACのuser_rolesしか触らずusers.roleカラムには
  触れない)ため、「非admin」の前提は崩れない。

  シナリオ9の検証用アカウントには、実際にログインしてアクセストークンを取得できる状態
  (Keycloak側のパスワード設定)まで必要になる。自己無効化の試行は「使い捨て管理者アカウント
  自身」のトークンで行う必要があるため。手順は`user-deactivation.feature`が使っている
  seed-acceptance-env.shの2/3手順(kcadmでのset-password、VERIFY_PROFILE用の
  firstName/lastName補完)に倣う。

  シナリオ: ROLE_MANAGE保有者は自分自身に特権ロールを付与できず、権限は変化しない
    前提 非adminのROLE_MANAGE保有者が使い捨てで用意されている
    もし そのROLE_MANAGE保有者が自分自身に特権ロールROLE_ADMINを付与しようとする
    ならば 権限不足として拒否される
    かつ そのROLE_MANAGE保有者の権限は操作前と変化していない

  シナリオ: 管理者は自分自身を無効化できず、アカウント状態は変化しない
    前提 使い捨ての管理者アカウントが用意されており、自分自身でログインできる
    もし その使い捨て管理者が自分自身の無効化を試みる
    ならば 権限不足として拒否される
    かつ その使い捨て管理者のアカウントは有効なまま変化していない
