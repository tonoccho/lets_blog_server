# language: ja
@project
機能: managed WordPress のメディアアップロード上限

  managed WordPress(`sites.managed_wordpress = 1`)は共有コンテナ `lbs-wordpress` の
  `/var/www/html/sites/{slug}` に設置され、`https://localhost/sites/{slug}/` で配信される。
  この経路には上限が3層ある(issue #1243):

    1. PHP の `upload_max_filesize` / `post_max_size`(Apache SAPI)
    2. reverse-proxy nginx の `/sites` location の `client_max_body_size`
    3. shared-host 前段 nginx の `client_max_body_size`(shared-host デプロイ時のみ)

  1層でも小さいと、WP管理画面のアップロードは 413 か「パラメータが不正です」で失敗する。
  どの層が律速でも同じ症状になるため、**経路全体を通す**ことでしか検証にならない。
  よってここは単体テストではなく、実際の WP 管理画面から実ファイルを送る受け入れシナリオで
  表現する。3層目(shared-host)は shared-host デプロイ時のみ経路に入り、通常の
  docker compose 環境からは到達できないため、`scripts/test_shared_host_proxy.py` が
  設定レベルで検証する(そちらに文書化した例外)。

  ## `@slow` な理由

  どちらのシナリオも ManagedWordPress を1サイト新規構築する(実測で最大240秒)。
  加えて2本目は 110MB の実ファイルを送るため、転送そのものにも時間がかかる。
  `npm run test:at:fast` は `@slow` を除外する。

  ## フィクスチャの後片付け

  110MB のフィクスチャはリポジトリに置かず、シナリオ内で `apps/web/test-results/` へ
  生成し、`After` フックで必ず削除する。dev のディスク枯渇は無関係なテストを巻き添えで
  落とす既知のハザードであり、生成物を残さないことは本シナリオの前提条件でもある。
  構築したサイト自体も `After` で削除する(siteAdoption.steps.ts と同じ方針)。

  @slow @timeout:420000
  シナリオ: WordPress管理画面が最大アップロードサイズとして1GBを表示する
    前提 ManagedWordPressサイトにWordPress管理者としてログインしている
    もし メディアの新規追加画面を開く
    ならば 最大アップロードサイズとして1GBが表示される

  @slow @timeout:600000
  シナリオ: 100MBを超えるファイルをWordPress管理画面からアップロードできる
    前提 ManagedWordPressサイトにWordPress管理者としてログインしている
    もし 110MBのファイルをメディアの新規追加画面からアップロードする
    ならば アップロードが成功しメディアライブラリに登録される
