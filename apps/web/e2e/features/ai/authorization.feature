# language: ja
@ai @api
機能: AI系エンドポイントの認可

  自分がメンバーでないプロジェクトのAI設定を読み書きできないことを固定する
  (issue #1150 / AT-8-5、親issue #934のシナリオ17・18を引き取る子issue)。

  ## シナリオ17(未認証401)は既存のマトリクス駆動シナリオで担保済み

  `docs/AUTHORIZATION_MATRIX.md` の `ProjectLlmModelController`(`/api/projects/{id}/ai-models/llm/**`)
  ・`ProjectBraveSearchApiKeyController`(`/api/projects/{projectId}/api-keys/brave-search-api-key`)
  ・`AiController`(`/api/ai/**`)の各行は、すべて「未認証」列が401になっている。
  `cross-cutting/authorization-matrix.feature`(AC-XC-001)の
  「認可マトリクスが未認証401としている全エンドポイントは、認証なしでは拒否される」が
  表を実行時に読み込んで全行を一斉に検証するため、AI系エンドポイントもそこに含まれて
  既に担保されている。表・実装のどちらにも不足は無かったため、ここに重複したシナリオは
  追加しない。

  ## シナリオ18(非メンバー403)は権限不足の判定方式が2系統ある

  `ProjectLlmModelController`(`/api/projects/{id}/ai-models/llm/**`)は
  `AdminAuthorizationService#requireAdmin()` — プロジェクトIDを見ない**グローバル管理者判定**
  である(認可表の備考に「現状維持」として明記済み。是正は本issueのスコープ外)。
  したがって一般利用者は、対象プロジェクトのメンバーであるか否かに関わらず拒否される。

  `ProjectBraveSearchApiKeyController`(`/api/projects/{projectId}/api-keys/brave-search-api-key`)は
  `requireProjectMemberOrAdmin(projectId)` — **プロジェクト単位のメンバー判定**である。
  こちらは自分のプロジェクトでは読み書きでき、他プロジェクトでは拒否される対照を確かめる
  (`analytics/analytics-authorization.feature` と同じ形)。

  横断マトリクスの一斉走査(`authorization-matrix.feature`)は、本文が必須の入力を要する
  エンドポイント(PUT/DELETE系)については「成功しないこと」までしか強制しない
  (本文を組み立てずに叩くと400で認可チェックに届かないため)。ここでは実際に妥当な本文を
  添えて要求し、GET/PUT/DELETEのすべてが403になることを確かめる。

  シナリオ: 一般利用者はプロジェクトのメンバーでなくてもLLM設定を読み書きできない
    前提 一般利用者がメンバーではないプロジェクトがある
    もし 一般利用者がそのプロジェクトのLLM設定エンドポイントをすべて要求する
    ならば LLM設定はすべてプロジェクトメンバーではないとして拒否される

  シナリオ: 自分がメンバーでないプロジェクトのBrave Search APIキーは読み書きできない
    前提 AI設定を確かめるプロジェクトが2つあり、一般利用者は片方だけのメンバーである
    ならば 一般利用者は自分のプロジェクトのBrave Search APIキーを読み書きできる
    もし 一般利用者が他プロジェクトのBrave Search APIキーエンドポイントをすべて要求する
    ならば Brave Search APIキーはすべてプロジェクトメンバーではないとして拒否される
    かつ 他プロジェクトのBrave Search APIキーは書き換えられていない
