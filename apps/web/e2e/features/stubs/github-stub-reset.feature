# language: ja
@stub @api @destructive
機能: GitHubスタブの初期化

  スタブの再起動または onReset(POST /__control/reset)で、PR・コメント・ファイル内容が
  すべて初期シードへ戻る(issue #1334)。リセットは Issue を含むスタブ全体の状態を戻すため、
  他のシナリオと並列に走らない @destructive とする。

  背景:
    前提 GitHubスタブが起動している

  シナリオ: リセットするとPR・コメント・ファイル内容が初期シードへ戻る
    もし head「リセット前のブランチ」・base「main」でPRを作成する
    かつ PR「201」へコメント「リセット前のコメント」を投稿する
    かつ シードのPR「201」をマージする
    かつ GitHubスタブをリセットする
    ならば PR一覧の番号はシードの「201」「202」だけである
    かつ PR「201」のコメント一覧は空である
    かつ シードのPR「201」は merged が false である
    かつ ref「main」に「articles/e2e-sample/article.md」は存在しない
