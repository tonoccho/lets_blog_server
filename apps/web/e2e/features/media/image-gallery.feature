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
