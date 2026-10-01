/** 情報表示レール「処理キュー」タブ(#1407)の、サーバー・クライアント双方から使う定数と純粋関数。 */

/** 狭いレールに収まる件数。これより古いジョブは /operation-logs で追う。 */
export const QUEUE_JOB_LIMIT = 10;

export const CHECKPOINT_DOWNLOAD_JOB_TYPE = "comfyui_checkpoint_download";
export const GARBAGE_COLLECTION_JOB_TYPE = "media_garbage_collection_delete";

export interface QueueJob {
  id: number;
  type: string;
  status: string;
  createdAt: string;
  /** 完了したジョブの「結果を見る」の遷移先。種別が未知、または導けないときは null。 */
  resultHref: string | null;
}

/** 進行中(ポーリングを続ける)状態か。useGenerationJobPolling と同じ running / pending。 */
export function isActiveJobStatus(status: string): boolean {
  return status === "running" || status === "pending";
}

function readProjectId(requestPayload: string | null): number | null {
  if (!requestPayload) return null;
  try {
    const parsed: unknown = JSON.parse(requestPayload);
    const id = (parsed as { projectId?: unknown } | null)?.projectId;
    return typeof id === "number" ? id : null;
  } catch {
    return null;
  }
}

/**
 * 完了したジョブの「結果を見る」の遷移先を種別から決める。
 *
 * - チェックポイント導入: リクエストに project ID が無い(url と fileName だけ)ため、チェックポイント
 *   一覧がある「AI・アセット」タブのプロジェクトを特定できない。プロジェクト一覧へ導く。
 * - メディアのガベージコレクション: リクエストの projectId から、そのプロジェクトの
 *   「ガベージコレクション」タブ(`?tab=garbage-collection`)へ導く。projectId が読めなければリンクなし。
 * - 未知の種別(画像生成など。#1408 が遷移先を定義する): リンクなし。
 */
export function resolveResultHref(type: string, requestPayload: string | null): string | null {
  if (type === CHECKPOINT_DOWNLOAD_JOB_TYPE) return "/projects";
  if (type === GARBAGE_COLLECTION_JOB_TYPE) {
    const projectId = readProjectId(requestPayload);
    return projectId === null ? null : `/projects/${projectId}?tab=garbage-collection`;
  }
  return null;
}
