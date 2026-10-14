import { GITHUB_AUTH_FAILED, GITHUB_TOKEN_NOT_CONFIGURED, type ApiErrorReason } from "@/lib/apiErrorReason";

/**
 * 記事計画Issue一覧の取得に失敗した原因を示す(issue #1771)。
 *
 * 汎用の `FetchErrorNotice` は「時間をおいて再読み込みしてください」としか言えず、待っても直らない
 * 設定の不備を待ち続けさせてしまう。この画面だけ、原因が分かるときは利用者が直す手順を示す。
 * 文面はウェブ側の固定文だけで作る。トークン、APIの `error` の本文、GitHubの応答本文、リポジトリ名は出さない。
 */
const MESSAGES: Record<ApiErrorReason, string> = {
  [GITHUB_TOKEN_NOT_CONFIGURED]:
    "記事計画Issue一覧を取得できませんでした。GitHub トークンが設定されていません。プロジェクト詳細の「GitHub Personal Access Token」か、ご自身のユーザー設定でトークンを設定してください。",
  [GITHUB_AUTH_FAILED]:
    "記事計画Issue一覧を取得できませんでした。GitHub の認証に失敗しました。トークンが無効、期限切れ、または権限不足の可能性があります。プロジェクト詳細かユーザー設定でトークンを設定し直してください。",
};

export function ArticlePlanIssueListError({ reason }: { reason: ApiErrorReason }) {
  return (
    <div
      role="alert"
      className="rounded-lg border border-red-200 dark:border-red-900 bg-red-50 dark:bg-red-950 p-4 text-sm text-red-800 dark:text-red-200"
    >
      {MESSAGES[reason]}
    </div>
  );
}
