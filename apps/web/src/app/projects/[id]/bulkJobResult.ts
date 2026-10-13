import type { BulkOperationLog } from "@/lib/apiClient";

/**
 * 全環境インストール(slug / zip)のジョブ(`bulk_slug_install` / `bulk_zip_install`、issue #1702)の結果を、
 * メンテナンスタブに出す形へ読み替える。
 *
 * - `done`: `resultPayload.logs` が環境ごとの成否(`BulkOperationLog` と同じ形。環境の順は呼び出し側が決めた順)。
 *   環境ごとの失敗は logs の `FAILED` で、ジョブ自体は `done`。
 * - `failed`: 操作全体の失敗(`resultPayload.error` に理由)。
 * - それ以外(`pending` / `running`): まだ実行中。
 */
export type BulkJobView =
  | { kind: "running" }
  | { kind: "failed"; error: string }
  | { kind: "done"; logs: BulkOperationLog[] }
  | { kind: "invalid" };

/** 全環境インストール(slug / zip)をジョブとして受理したときの表示。パネル間で文言を揃える。 */
export const BULK_INSTALL_ACCEPTED_MESSAGE =
  "全環境へのインストールを要求しました。処理キューに追加されました。完了後、処理キューの「結果を見る」から各環境の成否を確認できます。";

const DEFAULT_FAILURE = "全環境インストールに失敗しました。";

function parsePayload(resultPayload: string | null): Record<string, unknown> | null {
  if (resultPayload === null) return null;
  try {
    const value: unknown = JSON.parse(resultPayload);
    return typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

export function readBulkJobView(job: { status: string; resultPayload: string | null }): BulkJobView {
  if (job.status === "done") {
    const logs = parsePayload(job.resultPayload)?.logs;
    return Array.isArray(logs) ? { kind: "done", logs: logs as BulkOperationLog[] } : { kind: "invalid" };
  }
  if (job.status === "failed") {
    const error = parsePayload(job.resultPayload)?.error;
    return { kind: "failed", error: typeof error === "string" ? error : DEFAULT_FAILURE };
  }
  return { kind: "running" };
}
