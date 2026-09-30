# language: ja
@api @stub @plan
機能: Pull Request の head から記事一式を取得する API

  サイト管理者がレビュー対象の記事を確認・投稿できるよう、Pull Request の head にある記事 1 本分
  (front matter 込みの article.md と assets/ 配下の全ファイル)をサーバが取得して返す
  (issue #1338、Epic #1333)。記事ディレクトリは PR の変更ファイル一覧から `articles/<slug>/` を特定する。

  GitHub は**実サービスを叩かない**。publishing-service の `GITHUB_API_BASE_URL` を
  `docker-compose.e2e-stubs.yml` が `infra/e2e-stubs/github` のスタブへ向ける(#1334)。
  スタブのシード PR #201 のブランチには article.md(front matter なし)・cover.png・
  **1MB を超える large.png** が置かれている。これ以外の PR はシナリオごとに、スタブへ一意な
  ブランチとファイルを作って用意する。

  この API に Web 管理画面はまだ無い(後続 Issue が作る)ため `@api` で検証する。
  フィクスチャのプロジェクトは記事プランと同じもの(`@plan`)を使い、後片付けも共有する。

  シナリオ: 記事を含むPRを取得するとスラッグ・front matterの各項目・本文Markdownが返る
    前提 GitHub連携が設定されたプロジェクトが用意されている
    かつ 記事「fetch-sample」をfront matter付きで含むPRがスタブに用意されている
    もし 管理者がそのPRの記事を取得する
    ならば 記事の取得は成功する
    かつ 記事のスラッグは「fetch-sample」である
    かつ front matterのtitleは「取得サンプル」でstatusは「draft」である
    かつ front matterのcategoriesは「news」で、tagsは「alpha,beta」である
    かつ front matterのfeaturedImageは「assets/cover.png」でpublishScheduledAtは「2026-12-25T09:00:00Z」である
    かつ 本文Markdownに「取得サンプルの本文です」が含まれ、front matterの区切りは含まれない

  シナリオ: assetsの全ファイルがファイル名付きで列挙され、1MBを超えるファイルも欠落せず取得できる
    前提 GitHub連携が設定されたプロジェクトが用意されている
    もし 管理者がシードのPR「201」の記事を取得する
    ならば 記事の取得は成功する
    かつ assetsに「cover.png」と「large.png」が列挙される
    かつ assetsの「large.png」のサイズはスタブが持つ実サイズの1MBを超えるバイト数と一致する

  シナリオ: 記事ディレクトリを含まないPRでは記事が見つからないことが分かるエラーになる
    前提 GitHub連携が設定されたプロジェクトが用意されている
    かつ 記事ディレクトリを含まないPRがスタブに用意されている
    もし 管理者がそのPRの記事を取得する
    ならば 記事の取得は「404」で失敗し、エラーに「記事が見つかりません」が含まれる

  シナリオ: 記事ディレクトリを2つ以上含むPRでは1PR=1記事の前提に反することが分かるエラーになる
    前提 GitHub連携が設定されたプロジェクトが用意されている
    かつ 記事「first-article」と「second-article」を含むPRがスタブに用意されている
    もし 管理者がそのPRの記事を取得する
    ならば 記事の取得は「409」で失敗し、エラーに「1 PR = 1 記事」が含まれる

  シナリオ: 取得対象はPRのheadであり、headを進めると応答が変わる
    前提 GitHub連携が設定されたプロジェクトが用意されている
    かつ 記事「head-sample」のtitleが「初版」であるPRがスタブに用意されている
    もし 管理者がそのPRの記事を取得する
    ならば 記事の取得は成功する
    かつ front matterのtitleは「初版」である
    もし そのPRのheadブランチへtitleが「更新版」のコミットを積む
    かつ 管理者がそのPRの記事を取得する
    ならば 記事の取得は成功する
    かつ front matterのtitleは「更新版」である

  シナリオ: プロジェクトのメンバーでも管理者でもない利用者は拒否される
    前提 GitHub連携が設定されたプロジェクトが用意されている
    もし メンバーではない一般利用者がシードのPR「201」の記事を取得する
    ならば 記事の取得は「403」で失敗する
