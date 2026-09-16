# language: ja
#
# Ctrl+Shift+V(letsBlog.pasteSmartCard)はカード記法を即座に挿入し、キャッシュを
# バックグラウンドで温める。Ctrl+V(letsBlog.pasteAsLink)は`resolveContentCache`の
# 応答を待ってタイトル付きリンクを組み立て、取得に失敗すればURLのみへフォールバックする
# (issue #1071。AT-16(#942)で唯一未着手だったLayer 1、#1002で解消済み)。
#
# 検証に使う外部URLは`https://example.com/`を使う。取得内容の変化に影響されず安定しており、
# `docs/ACCEPTANCE_CRITERIA.md`のcontent-cache受け入れテスト(`content-cache.feature`)や
# `gateway-routing.feature`が既に同じURLを使っている。content-serviceのOutboundUrlGuard
# (#902)は内部アドレスを拒否するため、lbs-net上のスタブはこの検証には使えない。
@extension @api @articles
機能: URL貼り付け時のカード情報先読み

  背景:
    前提 拡張の設定が既定値である
    かつ 管理者としてログイン済みである

  シナリオ: pasteAsLinkはカード情報の先読みに成功するとタイトル付きリンクを組み立てる
    もし "https://example.com/" のカード情報を先読みしてリンク記法を組み立てる
    ならば リンク記法に取得したタイトルが含まれる

  シナリオ: pasteAsLinkはカード情報の先読みに失敗するとURLのみへフォールバックする
    もし "http://169.254.169.254/latest/meta-data/" のカード情報の先読みに失敗させてリンク記法を組み立てる
    ならば リンク記法はURLのみになる
