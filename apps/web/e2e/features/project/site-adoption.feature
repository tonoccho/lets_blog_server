# language: ja
@project
機能: 既存WordPressの取り込みと再プロビジョニング

  「取り込んだ既存WordPressを普通のサイトとして扱える」ことと「再プロビジョニングが
  既存の投稿データを壊さない」ことを固定する(issue #1169 / AT-5-5。親issue #931
  ([AT-5])の16シナリオのうち7・15を引き取る子issue、2026-09-08の分割)。

  wp-cli導入(親シナリオ14)は本issueのスコープに含めるはずだったが、実装調査の結果、
  ローカルの`wordpress`コンテナ(常駐エージェント経由の自動構築サイト)はDockerイメージへ
  ビルド時にwp-cliを導入済みで`install-wp-cli`が常にエラーになる設計であり、
  `install-wp-cli`が実際に対応するSSHトランスポートを検証できるSSHサーバがこのリポジトリの
  どのdocker-compose定義にも存在しない。外部の実サイトを使わずローカル実インフラだけで
  このエンドポイントを再現する手段が無いため、issue #1197としてブロッカーを切り出した
  (本ファイルでは扱わない)。

  ## フィクスチャの作り方(取り込みシナリオ)

  `POST /api/sites/managed-wordpress/adopt` は「provision-agent側には実体(ディレクトリ・
  wp-cliユーザー)が既にあるが、project-serviceのDBには未登録」のサイトを取り込む
  (`WordPressSiteProvisioningService.adoptManagedSite`)。この状態を作るのに、DBの行だけを
  agent側の実体を残したまま消す手段は無い(削除は常にdeprovisionを伴う)。そこで、
  既に`POST /api/sites/managed-wordpress`で構築済みの同じディレクトリ(slug)を指す
  **別のsiteKey**でadoptする。`normalizeSlug()`はsiteKey中の英数字とハイフン以外を
  ハイフンへ正規化するため、ハイフン区切りの元siteKeyに対してアンダースコア区切りの
  別siteKeyを使うと同じslugに正規化され、agent側は同じ既存ディレクトリ・同じ管理者
  ユーザーを見つけて取り込みに成功する(`site_key`のDB一意制約には抵触しない、
  文字として異なる値のため)。取り込んで新しくできるサイトは、元のサイトとは別の
  DBレコード(別id)だが、同じ実体を指す「取り込まれた既存WordPress」を模している。

  ## フィクスチャの作り方(再プロビジョニングシナリオ)

  `POST /api/sites/{id}/reprovision`(`SiteService.reprovision`)はカテゴリ・タグ・著者を
  再設定するだけで投稿には触れない。「投稿データが失われない」ことを検証するには実際の
  投稿が必要だが、project-serviceのAPIは(取り込み・構築のいずれでも)WordPressの
  アプリケーションパスワードを平文で返さない(`SiteService.getDetail`が秘匿フィールドを
  マスクする)ため、REST APIをBasic認証で直接叩けない。そこで、自分で指定した管理者
  ユーザー・パスワードでWordPress自身のログイン画面(`/sites/{slug}/wp-login.php`、
  project-serviceとは独立したWordPress本体の認証)からCookie認証し、投稿編集画面
  (`post-new.php`)が公開するREST APIノンス(`wpApiSettings.nonce`)を使って
  `wp-json/wp/v2/posts`へ直接投稿する。外部サイトも実サイトのモックも使わない。

  @api @slow
  シナリオ: 既存のWordPressをadoptで取り込むと、通常のサイトとして操作できる
    前提 provision-agent上に構築済みだが未取り込みのWordPressがある
    もし そのWordPressをadoptで取り込む
    ならば 取り込みが完了し取り込んだサイトが一覧に含まれる
    かつ 取り込んだサイトの疎通確認が成功する

  @slow @destructive
  シナリオ: 再プロビジョニングしても既存の投稿データは失われない
    前提 投稿がある状態のManagedWordPressサイトがある
    もし そのサイトを再プロビジョニングする
    ならば 再プロビジョニングが成功したことが応答でわかる
    かつ 投稿はそのまま取得できる
