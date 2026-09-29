# language: ja
@stub @api @mode:serial
機能: GitHubスタブ(Pull Request / contents / comments API)

  #1333 のサーバ側は GitHub の Pull Request API と contents API に依存する。
  スタブがこれらを GitHub REST API と同じ形で返さないと、受け入れテストが書けない(issue #1334)。
  このスタブが守る約束は、応答の形・決定的な採番・トークン別の失敗再現である。

  採番は「現在の最大値+1」なので、番号そのものではなく作成した PR の番号を使って検証する。
  初期シードへ戻る振る舞いは、スタブ全体を初期化するため github-stub-reset.feature(@destructive)にある。

  背景:
    前提 GitHubスタブが起動している

  シナリオ: PRを作成し、一覧・詳細・変更ファイル一覧・マージ・ブランチ削除を順に呼べる
    もし head「新しいブランチ」・base「main」でPRを作成する
    ならば PR作成の応答は 201 で、番号・head.ref・head.sha・base.ref・state「open」を持つ
    かつ open のPR一覧に作成したPRが含まれる
    かつ 作成したPRの詳細は mergeable が true で merged が false である
    かつ 作成したPRの変更ファイル一覧は配列で返る
    もし 作成したPRをマージする
    ならば マージの応答は 200 で merged が true である
    かつ 作成したPRの詳細は merged が true で state が「closed」である
    もし 作成したPRのブランチを削除する
    ならば ブランチ削除の応答は 204 である

  シナリオ: シードのPRの変更ファイルに記事本文と画像が含まれる
    もし シードのPR「201」の変更ファイル一覧を取得する
    ならば 変更ファイルに「articles/e2e-sample/article.md」が含まれる
    かつ 変更ファイルに「articles/e2e-sample/assets/cover.png」が含まれる

  シナリオ: 既定ブランチを取得できる
    もし リポジトリ情報を取得する
    ならば default_branch は「main」である

  シナリオ: 1MB以下のファイルはbase64のcontent付きで返る
    もし ref「article/e2e-sample」の「articles/e2e-sample/article.md」を contents API で取得する
    ならば content は base64 でデコードでき、記事本文で始まっている
    かつ sha が付いている

  シナリオ: 1MBを超えるファイルはcontentがnullでshaが返り、blobsで中身を取得できる
    もし ref「article/e2e-sample」の「articles/e2e-sample/assets/large.png」を contents API で取得する
    ならば content は null で、sha が付いている
    もし その sha で git/blobs を取得する
    ならば blob は base64 でデコードでき、1MBを超えるサイズである

  シナリオ: PRへ投稿したコメントは投稿順に一覧へ現れる
    もし PR「201」へコメント「1件目のコメント」を投稿する
    かつ PR「201」へコメント「2件目のコメント」を投稿する
    ならば PR「201」のコメント一覧の末尾は「1件目のコメント」「2件目のコメント」の順である

  シナリオ: 無効なトークンはPR系でも401になる
    もし トークン「e2e-stub-invalid-token」でPR一覧を取得する
    ならば 応答ステータスは 401 である

  シナリオ: 読み取り専用トークンはPR系の書き込みで403になる
    もし トークン「e2e-stub-readonly-token」でPRを作成する
    ならば 応答ステータスは 403 である

  シナリオ: レート制限トークンは403とX-RateLimit-Remaining 0になる
    もし トークン「e2e-stub-ratelimited-token」でPR一覧を取得する
    ならば 応答ステータスは 403 である
    かつ X-RateLimit-Remaining ヘッダは「0」である

  シナリオ: publishingサービスの向き先がGitHubスタブになる
    もし e2eスタブのcompose設定を展開する
    ならば publishing サービスの GITHUB_API_BASE_URL は github-stub を指している
    かつ publishing サービスは github-stub に depends_on している
