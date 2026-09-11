# language: ja
@media
機能: 生成画像ギャラリー

  作った画像を後から探し、どう作ったかを確かめ、分類し、要らなくなったら消す
  (issue #936 / AT-10、AC-IMG-003 / AC-IMG-004 / AC-IMG-005 / AC-IMG-006 / AC-IMG-007)。

  ## `image-upload.spec.ts` からの移行(docs/ACCEPTANCE_TESTING.md §7)

  このフィーチャは `apps/web/e2e/image-upload.spec.ts`(7テスト)の移行先である。
  移行にあたって、元の spec が持っていた主張のうち**受け入れ基準になるものだけ**を残した。

  | 元のテスト | 移行 |
  | --- | --- |
  | Navigate to image gallery page | シナリオ1へ統合(見出しの存在だけでは受け入れ基準にならない) |
  | Image gallery displays the fixture generated image | シナリオ1 |
  | Image detail modal opens and shows generation parameters | シナリオ2 |
  | Image deletion removes the fixture image from the gallery | シナリオ4 |
  | Local file upload input is not implemented | **移行しない**。「未実装であること」は受け入れ基準ではない(#645 の調査メモであって、仕様ではない) |
  | Search/filter input is not implemented | **移行しない**。同上。代わりに、実装されている**タグによる絞り込み**をシナリオ3で押さえる |
  | Responsive layout on mobile | **移行しない**。レスポンシブは AT-18(#944)の範囲(docs/ACCEPTANCE_TESTING.md §8) |

  元の spec は生成画像を**実際に ComfyUI で生成して**用意していたため、GPU の無い環境では
  `test.skip` に落ち、7テスト中4テストが恒常的に無検証だった(#645)。ここでは
  `POST /api/generated-images`(VSCode拡張が実際に使う保存経路)で行を作る。
  確かめたいのは「保存済みの画像をギャラリーがどう見せるか」であって、生成そのものは
  `image-generation.feature` が受け持つ。この分離により、GPU の有無に関わらず
  4シナリオすべてが実際に検証される。

  シナリオ: 保存済みの生成画像がギャラリーに一覧表示される
    前提 管理者としてログインする
    かつ プロンプト「E2E gallery listing fixture」の生成画像がギャラリーにある
    もし 生成画像ギャラリーを開く
    ならば ギャラリーにプロンプト「E2E gallery listing fixture」の画像が表示される

  シナリオ: 詳細モーダルに生成パラメータが表示される
    前提 管理者としてログインする
    かつ プロンプト「E2E gallery detail fixture」の生成画像がギャラリーにある
    もし 生成画像ギャラリーでその画像の詳細を開く
    ならば 詳細に「prompt」として「E2E gallery detail fixture」が表示される
    かつ 詳細に「steps」として「20」が表示される
    かつ 詳細に「size」として「512x512」が表示される
    かつ 詳細に「checkpoint」として「e2e.safetensors」が表示される

  シナリオ: 画像にタグを付けて保存でき、そのタグで絞り込める
    前提 管理者としてログインする
    かつ プロンプト「E2E gallery tagging fixture」の生成画像がギャラリーにある
    もし 生成画像ギャラリーでその画像の詳細を開く
    かつ タグ「e2e-936-tag」を追加する
    ならば 詳細のタグ一覧に「e2e-936-tag」が表示される
    かつ ギャラリーを開き直すとタグ「e2e-936-tag」で絞り込める

  シナリオ: 画像を削除するとギャラリーから消え、ファイル実体も取得できなくなる
    前提 管理者としてログインする
    かつ プロンプト「E2E gallery deletion fixture」の生成画像がギャラリーにある
    もし 生成画像ギャラリーでその画像の詳細を開く
    かつ 詳細から画像を削除する
    ならば ギャラリーにプロンプト「E2E gallery deletion fixture」の画像は表示されない
    かつ その画像のファイルはもう取得できない

  ## ログイン必須の画像配信のCache-Control(issue #1064)
  #
  # 生成画像の実体(`GET /image-gallery/{id}/file`)は先頭でセッションを確認し、未ログインなら
  # 401を返す。にもかかわらず応答ヘッダが `Cache-Control: public, max-age=3600` を返していたため、
  # ログアウト後や共有キャッシュから他人がブラウザキャッシュ経由で画像を取得できた
  # (セッション確認そのものを素通りしてしまう)。`no-store` に変更し、常にサーバーへ
  # 到達してセッション確認を通すようにする。
  #
  # 下の2シナリオは、ギャラリー画面(`/image-gallery`)を経由した「サムネイルをクリックして
  # 詳細を開く」操作を使わない。`ImageGalleryGrid` は生成日時をロケール整形して表示しており、
  # サーバー(コンテナ)とブラウザ(ホスト)のタイムゾーンが違うとハイドレーション不一致が起き、
  # 再描画のタイミングでクリックが失われることがある(issue #1236、本Issueとは無関係の既知の
  # 不具合)。ここで確かめたいのはCache-Controlとセッション/削除の関係であって画面遷移の経路
  # ではないため、ファイルURLへの直接アクセスとAPI呼び出しだけで検証する。
  #
  # 【この受け入れテスト環境での既知の限界】下の2シナリオ(ログアウト後・削除後)は、
  # `Cache-Control: public, max-age=3600`だった修正前のコードに対しても実行してみたが、
  # 同じ結果(画像は返らない)になった。調査の結果、この環境のChromium(Playwright、自己署名
  # 証明書のHTTPS)は本エンドポイントの応答をそもそもHTTPキャッシュへ保存していない
  # (`fetch(url, {cache:'force-cache'})`で直接確認済み。ブラウザのタイプ相違が原因の可能性が
  # 高いが未特定)。したがって下の2シナリオは「ブラウザキャッシュから返らないこと」の
  # red/green証跡にはならない(修正前後で結果が変わらないため)。この2つはセッション確認・
  # 削除確認そのものが壊れていないことを保証する回帰シナリオとして残す。
  # Cache-Controlの値そのもの(=どのキャッシュにも保存させないという契約)についての
  # red/green証跡は、上のシナリオと`route.test.ts`(jest単体テスト)が担う。

  シナリオ: 生成画像ファイルの応答はキャッシュを許可しない
    前提 管理者としてログインする
    かつ プロンプト「E2E gallery cache-control fixture」の生成画像がギャラリーにある
    もし その画像のファイルへ直接アクセスする
    ならば 画像の取得は成功する
    かつ 応答のCache-Controlに「public」は含まれない
    かつ 応答のCache-Controlは「no-store」を含む

  @destructive
  シナリオ: ログアウト後に同じ画像URLを直接開いても画像は返らない
    前提 管理者としてログインする
    かつ プロンプト「E2E gallery post-logout fixture」の生成画像がギャラリーにある
    もし その画像のファイルへ直接アクセスする
    かつ そのページのセッションをログアウトAPI経由で終了する
    かつ その画像のファイルへ直接アクセスする
    ならば ログイン画面へ転送され、画像は返らない

  シナリオ: 画像を削除すると、一度閲覧したはずのBFF経由の画像URLからも実体を取得できなくなる
    前提 管理者としてログインする
    かつ プロンプト「E2E gallery deletion file-route fixture」の生成画像がギャラリーにある
    もし その画像のファイルへ直接アクセスする
    かつ 画像の取得は成功する
    かつ その画像をAPI経由で削除する
    かつ その画像のファイルへ直接アクセスする
    ならば 画像の取得は成功しない
