# language: ja
@api @stub @ai @mode:serial
機能: 壁打ちのBrave Search併用とプロジェクト単位APIキー管理

  記事プランの壁打ち(`POST /api/projects/{projectId}/article-plan/chat`、
  ArticlePlanService#chat)は、応答生成前にBrave Searchを併用する。プロジェクトに
  Brave Search APIキーが設定されていればそれを優先し、未設定ならシステム全体設定へ
  フォールバックする(WebSearchService#searchSafely(query, projectId)、issue #184)。
  検索の成否は生成ジョブ(type=plan_chat)の記録に残るため、それを観測点にする
  (issue #1147 / AT-8-2、issue #934の子。scenario 7・8・9を引き継ぐ)。

  この受け入れテスト環境は BRAVE_SEARCH_API_KEY にプレースホルダ値
  (`changeme_brave_search_api_key`)が設定済みで、スタブ(infra/e2e-stubs/brave-search)は
  `e2e-stub-invalid-key` 以外のどのキーでも200を返す(決定的だが常に成功する)。そのため
  「プロジェクトにキーが未設定」を再現するだけでは、システム全体設定へのフォールバックが
  成功してしまいフェイルオープン経路を検証できない。WebSearchService#searchSafely
  (services/ai/src/main/java/com/letsblog/ai/service/WebSearchService.java:54-63)は
  Brave Search呼び出しの失敗理由を区別せずRuntimeExceptionを捕捉してフェイルオープンするため、
  「キー未設定」ではなく実際に検索呼び出しを失敗させることで同じ経路を通す
  (issue #934のブランチで先に確定した方針を踏襲)。

  ## なぜフィーチャ全体が `@mode:serial` か

  2つ目のシナリオは制御エンドポイントからBrave Searchスタブへ500を仕込む。仕込みは
  スタブ全体の状態であって、リクエスト単位ではない。並列に走らせると、1つ目・3つ目の
  シナリオの検索呼び出しがその500を横取りしうる(実測でそうなった: 1つ目のシナリオが
  横取りされ`webSearchSucceeded=false`になった)。Playwrightの直列化はdescribe単位で
  効くため、シナリオ単位ではなくフィーチャ全体に付ける(docs/ACCEPTANCE_TESTING.md §9、
  `ai/image-generation-chatgpt.feature`と同じ理由)。

  シナリオ: プロジェクトのBrave Search APIキーが設定されていると、壁打ちの回答がWeb検索結果を踏まえて生成される
    前提 AI設定用のプロジェクトが用意されている
    かつ そのプロジェクトのBrave Search APIキーを「e2e-stub-project-key」に設定する
    もし そのプロジェクトの壁打ちで「花粉症対策について」と発言する
    ならば 直近の壁打ちジョブはWeb検索に成功したと記録されている

  シナリオ: Brave Search呼び出しが失敗すると、壁打ちの回答はWeb検索結果なしでフェイルオープンする
    前提 AI設定用のプロジェクトが用意されている
    かつ Brave Searchが次のリクエストで500を返すよう仕込む
    もし そのプロジェクトの壁打ちで「花粉症対策について」と発言する
    ならば 直近の壁打ちジョブはWeb検索なしでフェイルオープンしたと記録されている

  シナリオ: プロジェクトのBrave Search APIキーを保存・削除でき、保存後のキーは平文で再表示されない
    前提 AI設定用のプロジェクトが用意されている
    もし そのプロジェクトのBrave Search APIキーを「e2e-stub-project-key」に設定する
    ならば そのプロジェクトのBrave Search APIキーは設定済みとして扱われ、値は含まれない
    もし そのプロジェクトのBrave Search APIキーを削除する
    ならば そのプロジェクトのBrave Search APIキーは未設定として扱われる
