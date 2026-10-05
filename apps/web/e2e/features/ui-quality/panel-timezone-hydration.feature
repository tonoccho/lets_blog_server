# language: ja
@ui-quality @ui @i18n @panel-timezone @account-isolation:timezone
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
  `ImageGalleryGrid`(画像ギャラリー)・`SiteListTable`(サイト一覧)・`PostsTable`
  (`/projects/[id]/posts`の投稿履歴)は`timezone: string | null`を親から素通しで受け取り
  ながら、未設定(null)のとき閲覧者のブラウザTZへ落ちる経路が無かった。ステップ定義
  (`e2e/steps/panelTimezone.steps.ts`)の`TARGET_PAGES`を画面ごとに引数化して再利用し、
  下の6シナリオを追加する。`BulkManagementPanel`(一括管理のログコピー)は
  `describeLogText()`がコピーボタンの`onClick`からしか呼ばれず、サーバー描画時の値が
  存在しないためハイドレーション不一致が起こり得ない(Readiness Report参照)。この画面
  だけは単体テスト(`BulkManagementPanel.copyLog.test.tsx`)で確認しており、ここには
  シナリオを追加しない。

  シナリオで使うフィクスチャは画面ごとに性質が異なるため使い分ける。
  - 画像ギャラリー: `media.steps.ts`の「seedを持たないChatGPT画像がギャラリーにある」
    (issue #1101)をそのまま再利用する。
  - サイト一覧: `cross-cutting.steps.ts`の`POST /api/sites`パターン(issue #830)を踏襲した
    専用フィクスチャ(`TZ検証用のサイトが1件登録されている`)を使う。
  - 投稿履歴: `lastPublishedAt`は実際に公開しないと入らないため、
    `publishing/publish-lifecycle.feature`(issue #1171)の
    「公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている」と
    「記事を新規公開する」をそのまま再利用する。WordPress自動構築を伴う重い経路のため、
    この2シナリオには`@publishing @slow`を追加で付けている(直列化は
    ファイル単位の`@account-isolation:timezone`が担う。issue #1374参照)。

  ## issue #1364(親issue #1261 分割C)による拡張

  分割A・Bとは異なり、`/users`・`/projects`・`/posts`(いずれもトップレベル一覧画面)は
  クライアント部品を経由せず、サーバーコンポーネントの JSX 内で直接`formatDateTime`を
  呼んでいた。サーバーコンポーネントにはマウント後の処理が無く、ブラウザTZを当てる
  クライアント境界が無かったため、分割Bのようにゲートを差し込む先すら存在しなかった。
  分割A/Bで複製されていた同じゲート(`personalTimeZone ? ... : mounted ? ... : `
  `TIMEZONE_PENDING_PLACEHOLDER`)を共有クライアント部品`ViewerDateTime`
  (`apps/web/src/components/ViewerDateTime.tsx`)へ切り出し、3画面それぞれの日時セルに
  差し込む。`TARGET_PAGES`にこの3画面を追加し、下の6シナリオを追加する。

  シナリオで使うフィクスチャも画面ごとに使い分ける。
  - ユーザー管理: `userManagement.steps.ts`等が使う`POST /api/users`パターンを踏襲した
    専用フィクスチャ(`TZ検証用のユーザーが1件登録されている`)を使う。
  - プロジェクト: `diagram.steps.ts`等が使う`POST /api/projects`パターンを踏襲した
    専用フィクスチャ(`TZ検証用のプロジェクトが1件登録されている`)を使う。
  - 投稿履歴(トップレベル`/posts`): 分割Bの投稿履歴シナリオと同じ理由で、
    `publishing/publish-lifecycle.feature`(issue #1171)の
    「公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている」と
    「記事を新規公開する」をそのまま再利用する。`/posts`は`GET /api/posts`(全プロジェクト
    横断)を見るため、分割Bが`/projects/{id}/posts`で公開した投稿がそのままここにも現れる。
    ただし表の列構成が異なる(カテゴリ列が無い)ため、セル位置を突き合わせる`Then`ステップは
    分割Bの`その投稿の最終投稿日時が...`とは別に用意し、この2シナリオにも分割Bと同じ
    `@publishing @slow`を付けている(直列化はファイル単位の`@account-isolation:timezone`
    が担う。issue #1374参照)。

  ## issue #1366(親issue #1261 分割B-2)による拡張

  分割Bの一覧系(`ImageGalleryGrid`・`SiteListTable`・`PostsTable`)とは異なり、
  `ArticlePlanSessionList`(`/projects/{id}/plan`の壁打ち一覧)はタイムゾーンを受け取る
  経路そのものが無く、独自の`formatSessionDate()`が`new Date(iso).getFullYear()`等の
  ローカル取得で作成日(`YYYYMMDD`、区切りなし)を組み立てていた。`plan/page.tsx`に
  `getViewerTimeZone()`を足し、`ArticlePlanWorkspace`を経由して`ArticlePlanSessionList`へ
  渡す経路を新設した。表示形式が`YYYYMMDD`(区切りなし)で`ViewerDateTime`
  (`formatDateTime`のロケール文字列をそのまま描く作り)とは差し替えできないため、
  `ViewerDateTime`は使わず`PostsTable`と同じ「ゲートを直接書く」形にした
  (Readiness Report参照)。

  壁打ちセッションのフィクスチャは`article-plan/planning-session.feature`(issue #935)と
  同じ、既存のスタブ利用チャット経由で作る(`articlePlan.steps.ts`の
  「記事計画用のプロジェクトが用意されている」「記事計画画面を開く」
  「壁打ちで「X」と発言する」をそのまま再利用する)。`createdAt`は
  `ArticlePlanSessionSummaryResponse.java`の`LocalDateTime`(オフセット無し)なので、
  他のTZシナリオと同じく`GET /api/projects/{id}/article-plan/sessions`から永続化された
  値を再取得する。

  **タイムゾーンを固定文字列で書かない理由(レビュー指摘、2026-09-20)**: `createdAt`は
  実行時刻(サーバ、UTC)そのものなので、`Asia/Tokyo`や`Pacific/Auckland`のような固定の
  タイムゾーン名をシナリオに書くと、UTCの暦日とたまたま一致する時間帯(実測: それぞれ
  15:00-23:59 UTC・12:00-23:59 UTC)に実行すると、直していない実装(生の`createdAt`の
  暦日をそのまま出すだけ)でもたまたま一致してPASSしてしまい、実行する時刻によっては
  バグを検出できない。下の2シナリオは、実際に作られたセッションの`createdAt`を取得した
  「後」に、UTCの生の暦日と必ず食い違うタイムゾーン(`Pacific/Kiritimati`・`Pacific/Niue`の
  組み合わせ。2つの一致時間帯が重ならないよう選んでいるため、どちらか一方は必ず食い違う。
  詳細は`panelTimezone.steps.ts`の`pickDivergentTimezone`のコメント参照)をその場で選んで
  使うため、実行するどの時刻でも直していない実装は必ず失敗する。

  ## issue #1374: 既定の並列実行でのシナリオ間の競合

  このファイルの18シナリオは全て、`panelTimezone.steps.ts`/`media.steps.ts`の
  「個人設定のタイムゾーンを「X」に変更する」「個人設定のタイムゾーンを未設定にする」で
  **単一の共有管理者アカウント**(`E2E_ADMIN_EMAIL`)の`/api/identity/me/preferences`を
  直接書き換える。`fullyParallel: true`の既定の並列数では、複数ワーカーがこれを同時に
  書き換えて奪い合い、期待した換算値と実際の表示がずれる形で大量に失敗する
  (2026-09-21実測: 14/18失敗)。同じ管理者設定は`media/image-gallery.feature`・
  `ui-quality/internationalization.feature`の各1シナリオも書き換えるため、
  このファイル単体を直列化するだけでは実際の実行(`npm run test:at`)での衝突は残らない。
  ファイル冒頭のタグ`@account-isolation:timezone`により、この3ファイルの該当シナリオを
  `playwright.config.ts`の専用プロジェクト`at-timezone-exclusive`(`workers: 1`、
  `at-main`と並列)へ集約して直列化する(#1188の`at-llm-exclusive`と同型の対処。
  詳細は`playwright.config.ts`のコメント、`docs/ACCEPTANCE_TESTING.md` §9参照)。

  以前あった173・182・219・228行目のシナリオ単位`@mode:serial`は、playwright-bddが
  素のシナリオでは`describe.configure`を生成しないため**生成物に一切反映されておらず**、
  効果が無かった(`docs/ACCEPTANCE_TESTING.md` §9参照)。ファイル全体を専用プロジェクトへ
  移した今は、そのプロジェクトの`workers: 1`がファイル内の直列化(WordPress公開を伴う
  4シナリオを含む)を代わりに担うため、このタグは削除した。

  @stub @plan
  シナリオ: 個人設定TZが設定されているなら、ブラウザTZに関係なく壁打ち一覧の作成日が個人設定TZ換算で表示される
    前提 管理者としてログインする
    かつ 記事計画用のプロジェクトが用意されている
    かつ 記事計画画面を開く
    かつ 壁打ちで「TZ検証用の発言」と発言する
    もし そのセッションの作成日が暦日をまたぐタイムゾーンを個人設定にし、対極のタイムゾーンをブラウザTZにして管理者としてログインし、記事計画を開く
    ならば 壁打ち一覧のセッションの作成日が、選んだタイムゾーンへの換算値のYYYYMMDDと一致する
    かつ コンソールにハイドレーションエラーが記録されない

  @stub @plan
  シナリオ: 個人設定TZが未設定なら、ブラウザTZ換算(暦日をまたぐ値)で壁打ち一覧の作成日が表示される
    前提 個人設定のタイムゾーンを未設定にする
    かつ 管理者としてログインする
    かつ 記事計画用のプロジェクトが用意されている
    かつ 記事計画画面を開く
    かつ 壁打ちで「TZ検証用の発言」と発言する
    もし そのセッションの作成日が暦日をまたぐタイムゾーンをブラウザTZにして管理者としてログインし、記事計画を開く
    ならば 壁打ち一覧のセッションの作成日が、選んだタイムゾーンへの換算値のYYYYMMDDと一致する
    かつ コンソールにハイドレーションエラーが記録されない

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
  シナリオ: 個人設定TZがAsia/Tokyoなら、ブラウザTZに関係なく画像ギャラリーの日時がTokyo換算で表示される
    前提 個人設定のタイムゾーンを「Asia/Tokyo」に変更する
    かつ seedを持たないChatGPT画像がギャラリーにある
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、画像ギャラリー画面を開く
    ならば 画像ギャラリーのその画像の作成日時が「Asia/Tokyo」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  @media
  シナリオ: 個人設定TZが未設定なら、ブラウザTZ(Pacific/Auckland)換算で画像ギャラリーの日時が表示される
    前提 個人設定のタイムゾーンを未設定にする
    かつ seedを持たないChatGPT画像がギャラリーにある
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、画像ギャラリー画面を開く
    ならば 画像ギャラリーのその画像の作成日時が「Pacific/Auckland」への換算値と一致する
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

  @publishing @slow
  シナリオ: 個人設定TZがAsia/Tokyoなら、ブラウザTZに関係なく投稿履歴の最終投稿日時がTokyo換算で表示される
    前提 個人設定のタイムゾーンを「Asia/Tokyo」に変更する
    かつ 公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている
    かつ 記事を新規公開する
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、投稿履歴を開く
    ならば その投稿の最終投稿日時が「Asia/Tokyo」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  @publishing @slow
  シナリオ: 個人設定TZが未設定なら、ブラウザTZ(Pacific/Auckland)換算で投稿履歴の最終投稿日時が表示される
    前提 個人設定のタイムゾーンを未設定にする
    かつ 公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている
    かつ 記事を新規公開する
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、投稿履歴を開く
    ならば その投稿の最終投稿日時が「Pacific/Auckland」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  シナリオ: 個人設定TZがAsia/Tokyoなら、ブラウザTZに関係なくユーザー一覧の登録日がTokyo換算で表示される
    前提 個人設定のタイムゾーンを「Asia/Tokyo」に変更する
    かつ TZ検証用のユーザーが1件登録されている
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、ユーザー管理を開く
    ならば そのユーザーの登録日が「Asia/Tokyo」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  シナリオ: 個人設定TZが未設定なら、ブラウザTZ(Pacific/Auckland)換算でユーザー一覧の登録日が表示される
    前提 個人設定のタイムゾーンを未設定にする
    かつ TZ検証用のユーザーが1件登録されている
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、ユーザー管理を開く
    ならば そのユーザーの登録日が「Pacific/Auckland」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  シナリオ: 個人設定TZがAsia/Tokyoなら、ブラウザTZに関係なくプロジェクト一覧の作成日がTokyo換算で表示される
    前提 個人設定のタイムゾーンを「Asia/Tokyo」に変更する
    かつ TZ検証用のプロジェクトが1件登録されている
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、プロジェクトを開く
    ならば そのプロジェクトの作成日が「Asia/Tokyo」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  シナリオ: 個人設定TZが未設定なら、ブラウザTZ(Pacific/Auckland)換算でプロジェクト一覧の作成日が表示される
    前提 個人設定のタイムゾーンを未設定にする
    かつ TZ検証用のプロジェクトが1件登録されている
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、プロジェクトを開く
    ならば そのプロジェクトの作成日が「Pacific/Auckland」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  @publishing @slow
  シナリオ: 個人設定TZがAsia/Tokyoなら、ブラウザTZに関係なく投稿一覧の最終投稿日時がTokyo換算で表示される
    前提 個人設定のタイムゾーンを「Asia/Tokyo」に変更する
    かつ 公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている
    かつ 記事を新規公開する
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、投稿履歴一覧を開く
    ならば 投稿一覧のその投稿の最終投稿日時が「Asia/Tokyo」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない

  @publishing @slow
  シナリオ: 個人設定TZが未設定なら、ブラウザTZ(Pacific/Auckland)換算で投稿一覧の最終投稿日時が表示される
    前提 個人設定のタイムゾーンを未設定にする
    かつ 公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている
    かつ 記事を新規公開する
    もし ブラウザのタイムゾーンを「Pacific/Auckland」にして管理者としてログインし、投稿履歴一覧を開く
    ならば 投稿一覧のその投稿の最終投稿日時が「Pacific/Auckland」への換算値と一致する
    かつ コンソールにハイドレーションエラーが記録されない
