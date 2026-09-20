# language: ja
@ui-quality @ui @i18n @panel-timezone
機能: ダッシュボード/管理画面パネルの日時表示とハイドレーション不一致の防止

  `ConnectedServiceStatusPanel`(ダッシュボードの管理者向け詳細診断)と`SshKeyPairsPanel`
  (SSH鍵管理ページ)は、個人設定TZを受け取る経路が無いまま`formatDateTime`をTZ引数なしで
  呼んでいたため、SSR(コンテナTZ)とブラウザで表示が食い違い、ハイドレーション不一致に
  なっていた(issue #1362、親issue #1261 分割A)。2026-09-19のリリース検証
  (`media/image-gallery.feature:41`)は、ログイン直後のダッシュボードでこの不一致を実際に
  検出して失敗した。

  「個人設定のタイムゾーンを「X」に変更する」と「コンソールにハイドレーションエラーが
  記録されない」は、`media/image-gallery.feature`(issue #1236)が既に持つ同型の受け入れ
  シナリオと同じ汎用ステップ(`e2e/steps/media.steps.ts`)を再利用する。

  ## issue #1363(親issue #1261 分割B)による拡張

  分割Aと同型の不備が、既にTZを受け取っている一覧系クライアント部品にも見つかった。
  `ImageGalleryGrid`(生成画像ギャラリー)・`SiteListTable`(サイト一覧)・`PostsTable`
  (`/projects/[id]/posts`の投稿履歴)は`timezone: string | null`を親から素通しで受け取り
  ながら、未設定(null)のとき閲覧者のブラウザTZへ落ちる経路が無かった。ステップ定義
  (`e2e/steps/panelTimezone.steps.ts`)の`TARGET_PAGES`を画面ごとに引数化して再利用し、
  下の6シナリオを追加する。`BulkManagementPanel`(一括管理のログコピー)は
  `describeLogText()`がコピーボタンの`onClick`からしか呼ばれず、サーバー描画時の値が
  存在しないためハイドレーション不一致が起こり得ない(Readiness Report参照)。この画面
  だけは単体テスト(`BulkManagementPanel.copyLog.test.tsx`)で確認しており、ここには
  シナリオを追加しない。

  シナリオで使うフィクスチャは画面ごとに性質が異なるため使い分ける。
  - 生成画像ギャラリー: `media.steps.ts`の「seedを持たないChatGPT画像がギャラリーにある」
    (issue #1101)をそのまま再利用する。
  - サイト一覧: `cross-cutting.steps.ts`の`POST /api/sites`パターン(issue #830)を踏襲した
    専用フィクスチャ(`TZ検証用のサイトが1件登録されている`)を使う。
  - 投稿履歴: `lastPublishedAt`は実際に公開しないと入らないため、
    `publishing/publish-lifecycle.feature`(issue #1171)の
    「公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている」と
    「記事を新規公開する」をそのまま再利用する。WordPress自動構築を伴う重い経路のため、
    この2シナリオだけ`@publishing @slow @mode:serial`を追加で付けている。

  シナリオ: 個人設定TZがAsia/Tokyoなら、ブラウザTZに関係なく接続サービス詳細のチェック時刻がTokyo換算で表示される
    前提 個人設定のタイムゾーンを「Asia/Tokyo」に変更する
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、ダッシュボードを開く
    ならば 接続サービス詳細のチェック時刻が「Asia/Tokyo」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  シナリオ: 個人設定TZが未設定なら、ブラウザTZ(Pacific/Auckland)換算で接続サービス詳細のチェック時刻が表示される
    前提 個人設定のタイムゾーンを未設定にする
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、ダッシュボードを開く
    ならば 接続サービス詳細のチェック時刻が「Pacific/Auckland」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  シナリオ: 個人設定TZがAsia/Tokyoなら、ブラウザTZに関係なくSSH鍵ペアの作成日時がTokyo換算で表示される
    前提 個人設定のタイムゾーンを「Asia/Tokyo」に変更する
    かつ TZ検証用のSSH鍵ペアが1件登録されている
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、SSH鍵管理ページを開く
    ならば そのSSH鍵ペアの作成日時が「Asia/Tokyo」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  シナリオ: 個人設定TZが未設定なら、ブラウザTZ(Pacific/Auckland)換算でSSH鍵ペアの作成日時が表示される
    前提 個人設定のタイムゾーンを未設定にする
    かつ TZ検証用のSSH鍵ペアが1件登録されている
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、SSH鍵管理ページを開く
    ならば そのSSH鍵ペアの作成日時が「Pacific/Auckland」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  @media
  シナリオ: 個人設定TZがAsia/Tokyoなら、ブラウザTZに関係なく生成画像ギャラリーの日時がTokyo換算で表示される
    前提 個人設定のタイムゾーンを「Asia/Tokyo」に変更する
    かつ seedを持たないChatGPT画像がギャラリーにある
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、生成画像ギャラリー画面を開く
    ならば 生成画像ギャラリーのその画像の作成日時が「Asia/Tokyo」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  @media
  シナリオ: 個人設定TZが未設定なら、ブラウザTZ(Pacific/Auckland)換算で生成画像ギャラリーの日時が表示される
    前提 個人設定のタイムゾーンを未設定にする
    かつ seedを持たないChatGPT画像がギャラリーにある
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、生成画像ギャラリー画面を開く
    ならば 生成画像ギャラリーのその画像の作成日時が「Pacific/Auckland」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  シナリオ: 個人設定TZがAsia/Tokyoなら、ブラウザTZに関係なくサイト一覧の登録日がTokyo換算で表示される
    前提 個人設定のタイムゾーンを「Asia/Tokyo」に変更する
    かつ TZ検証用のサイトが1件登録されている
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、サイト一覧を開く
    ならば そのサイトの登録日が「Asia/Tokyo」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  シナリオ: 個人設定TZが未設定なら、ブラウザTZ(Pacific/Auckland)換算でサイト一覧の登録日が表示される
    前提 個人設定のタイムゾーンを未設定にする
    かつ TZ検証用のサイトが1件登録されている
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、サイト一覧を開く
    ならば そのサイトの登録日が「Pacific/Auckland」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  @publishing @slow @mode:serial
  シナリオ: 個人設定TZがAsia/Tokyoなら、ブラウザTZに関係なく投稿履歴の最終投稿日時がTokyo換算で表示される
    前提 個人設定のタイムゾーンを「Asia/Tokyo」に変更する
    かつ 公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている
    かつ 記事を新規公開する
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、投稿履歴を開く
    ならば その投稿の最終投稿日時が「Asia/Tokyo」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  @publishing @slow @mode:serial
  シナリオ: 個人設定TZが未設定なら、ブラウザTZ(Pacific/Auckland)換算で投稿履歴の最終投稿日時が表示される
    前提 個人設定のタイムゾーンを未設定にする
    かつ 公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている
    かつ 記事を新規公開する
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、投稿履歴を開く
    ならば その投稿の最終投稿日時が「Pacific/Auckland」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない
