/**
 * アセット画像の全環境アップロードのジョブ(`asset_image_upload`、issue #1764)の結果を、
 * 「AI・アセット」タブのパネルが出すメッセージへ読み替える。文言は同期だった頃(#1688 まで)と揃える。
 *
 * - `done`: `resultPayload.logs` が環境ごとの成否(`BulkOperationLog` と同じ形)。環境ごとの失敗(画素数の
 *   上限による拒否を含む)は logs の `FAILED` で、ジョブ自体は `done`。
 * - `failed`: 操作全体の失敗(`resultPayload.error` に理由)。
 * - それ以外(`pending` / `running`): まだ実行中。
 */
export interface AssetUploadMessage {
  type: "success" | "error" | "info";
  text: string;
}

/** アセットとしての追加をジョブとして受理したときの表示。完了ではない。 */
export const ASSET_UPLOAD_ACCEPTED_MESSAGE =
  "アセットとしての追加を要求しました。処理キューに追加されました。完了後、処理キューの「結果を見る」から各環境の成否を確認できます。";

/** 実行枠と待ち行列が満杯で、ジョブが failed のまま返ったとき。 */
export const ASSET_UPLOAD_QUEUE_FULL_ERROR =
  "アセット画像のアップロードの待ち行列が満杯です。しばらくしてからもう一度要求してください。";

const DEFAULT_FAILURE = "アセットとしての追加に失敗しました。";

function parsePayload(resultPayload: string | null): Record<string, unknown> | null {
  if (resultPayload === null) return null;
  try {
    const value: unknown = JSON.parse(resultPayload);
    return typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

export function describeAssetUploadJob(job: { status: string; resultPayload: string | null }): AssetUploadMessage {
  if (job.status === "done") {
    const logs = parsePayload(job.resultPayload)?.logs;
    if (!Array.isArray(logs)) return { type: "error", text: "結果を読み取れませんでした。" };
    const failed = (logs as { environment: string; status: string }[]).filter((l) => l.status !== "SUCCESS");
    if (failed.length === 0) return { type: "success", text: `全${logs.length}環境へアップロードしました。` };
    return { type: "error", text: `${failed.map((l) => l.environment).join(", ")}環境でアップロードに失敗しました。` };
  }
  if (job.status === "failed") {
    const error = parsePayload(job.resultPayload)?.error;
    return { type: "error", text: typeof error === "string" ? error : DEFAULT_FAILURE };
  }
  return { type: "info", text: "まだ実行中です。完了後に、処理キューの「結果を見る」からもう一度開いてください。" };
}
