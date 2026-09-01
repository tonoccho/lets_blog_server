# language: ja
@extension @api @articles @slow
機能: 記事の公開

  背景:
    前提 拡張の設定が既定値である
    かつ 管理者としてログイン済みである
    かつ 受け入れテスト用のプロジェクトが存在する
    かつ 公開先のマネージドWordPressサイトが用意されている

  シナリオ: front matter付きのMarkdownを公開するとWordPress投稿が作成される
    もし スラッグ "at16-publish" の記事を下書きとして公開する
    ならば WordPress投稿のIDと公開URLが返る
    かつ 公開した記事はスラッグから照会できる

  シナリオ: 公開済みの記事を再度公開すると同じWordPress投稿が更新される
    前提 スラッグ "at16-update" の記事を下書きとして公開する
    もし 同じスラッグの記事を本文を変えて再度公開する
    ならば 同じWordPress投稿IDが返る
