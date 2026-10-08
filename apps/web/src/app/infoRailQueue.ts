/** 情報表示レール「処理キュー」タブ(#1407)の、サーバー・クライアント双方から使う定数と純粋関数。 */

/** 狭いレールに収まる件数。これより古いジョブは /operation-logs で追う。 */
export const QUEUE_JOB_LIMIT = 10;

export const CHECKPOINT_DOWNLOAD_JOB_TYPE = "comfyui_checkpoint_download";
export const GARBAGE_COLLECTION_JOB_TYPE = "media_garbage_collection_delete";
/** 非同期の画像生成ジョブの種別(media の `ImageGenerationJobStarter.JOB_TYPE`)。 */
export const IMAGE_GENERATION_JOB_TYPE = "image_generation";
/** カスタムタグのAI生成ジョブの種別(content の `CustomTagGenerationJobStarter.JOB_TYPE`、#1409)。 */
export const CUSTOM_TAG_GENERATION_JOB_TYPE = "custom_tag_generation";
/** 静的コンテンツのAI生成ジョブの種別(project の `TextGenerationJobStarter.JOB_TYPE_STATIC_CONTENT`、#1409)。 */
export const STATIC_CONTENT_GENERATION_JOB_TYPE = "static_content_generation";
/** タグデザインのAI生成ジョブの種別(project の `TextGenerationJobStarter.JOB_TYPE_TAG_DESIGN`、#1409)。 */
export const TAG_DESIGN_GENERATION_JOB_TYPE = "tag_design_generation";

/** サイト自動構築ジョブの種別(project の `ManagedSiteProvisioningJobStarter.JOB_TYPE`、#1479)。 */
export const SITE_PROVISIONING_JOB_TYPE = "site_provisioning";

export interface QueueJob {
  id: number;
  type: string;
  status: string;
  createdAt: string;
  /** 完了したジョブの「結果を見る」の遷移先。種別が未知、または導けないときは null。 */
  resultHref: string | null;
  /** 受理後に失敗したジョブの失敗理由(`resultPayload.error`、短く切り詰め済み)。失敗でない、または読めないときは null。 */
  failureReason: string | null;
}

/** 狭いレールに収まる失敗理由の最大文字数(超えたら末尾を省略記号にする)。 */
export const FAILURE_REASON_MAX_LENGTH = 120

/** 進行中(ポーリングを続ける)状態か。useGenerationJobPolling と同じ running / pending。 */
export function isActiveJobStatus(status: string): boolean {
  return status === "running" || status === "pending";
}

/** リクエスト内容(JSON文字列)をオブジェクトとして読む。読めなければ null。 */
function readPayloadObject(requestPayload: string | null): Record<string, unknown> | null {
  if (!requestPayload) return null;
  try {
    const parsed: unknown = JSON.parse(requestPayload);
    return parsed !== null && typeof parsed === "object" ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function readNumberField(requestPayload: string | null, key: string): number | null {
  const value = readPayloadObject(requestPayload)?.[key];
  return typeof value === "number" ? value : null;
}

function readProjectId(requestPayload: string | null): number | null {
  return readNumberField(requestPayload, "projectId");
}

/**
 * 完了したジョブの「結果を見る」の遷移先を種別から決める。
 *
 * - チェックポイント導入: リクエストに project ID が無い(url と fileName だけ)ため、チェックポイント
 *   一覧がある「AI・アセット」タブのプロジェクトを特定できない。プロジェクト一覧へ導く。
 * - メディアのガベージコレクション: リクエストの projectId から、そのプロジェクトの
 *   「ガベージコレクション」タブ(`?tab=garbage-collection`)へ導く。projectId が読めなければリンクなし。
 * - 画像生成: リクエストに project ID が無い(prompt・provider・枚数だけ)ため、ここでは決められない。
 *   生成された画像の所属プロジェクトから `buildImageGenerationResultHref` で組み立てる(`fetchQueueJobsAction`、#1408)。
 * - カスタムタグ・静的コンテンツ・タグデザインのAI生成(#1409): 生成結果はジョブの結果にだけあるので、
 *   リクエストの projectId / siteId から各機能の画面を決め、`jobId` でその結果を指す。`jobId` が無い、
 *   画面を決められない(カスタムタグでプロジェクトが無いなど)ときはリンクなし。
 * - 未知の種別: リンクなし。
 */
export function resolveResultHref(type: string, requestPayload: string | null, jobId: number | null = null): string | null {
  if (type === CHECKPOINT_DOWNLOAD_JOB_TYPE) return "/projects";
  if (jobId !== null) {
    if (type === CUSTOM_TAG_GENERATION_JOB_TYPE) {
      const projectId = readProjectId(requestPayload);
      return projectId === null ? null : buildCustomTagResultHref(projectId, jobId);
    }
    if (type === STATIC_CONTENT_GENERATION_JOB_TYPE) {
      const siteId = readNumberField(requestPayload, "siteId");
      return siteId === null ? null : buildStaticContentResultHref(siteId, jobId);
    }
    if (type === TAG_DESIGN_GENERATION_JOB_TYPE) {
      const payload = readPayloadObject(requestPayload);
      if (payload === null) return null;
      // projectId が null(明示)ならグローバル既定。無い・数値でないものは画面を決められない。
      if (payload.projectId === null) return buildTagDesignResultHref(null, jobId);
      const projectId = readProjectId(requestPayload);
      return projectId === null ? null : buildTagDesignResultHref(projectId, jobId);
    }
  }
  if (type === GARBAGE_COLLECTION_JOB_TYPE) {
    const projectId = readProjectId(requestPayload);
    return projectId === null ? null : `/projects/${projectId}?tab=garbage-collection`;
  }
  return null;
}

/**
 * 画像生成ジョブの「結果を見る」の遷移先(#1408)。そのプロジェクトの「AI・アセット」タブを開き、
 * `imageJob` で指したジョブが生成した画像だけをアセット画像生成パネルに表示する。
 * 生成画像の選択・アップロードはそのパネルが従来どおり行うので、結果の表示部分を作り直さずに済む。
 */
export function buildImageGenerationResultHref(projectId: number, jobId: number): string {
  return `/projects/${projectId}?tab=ai-models&imageJob=${jobId}`;
}

/**
 * カスタムタグ生成ジョブの「結果を見る」の遷移先(#1409)。そのプロジェクトの「カスタムタグ管理」タブを開き、
 * `customTagJob` で指したジョブの生成結果(未保存)を表示する。「保存」で初めて `custom_tags` へ登録される。
 */
export function buildCustomTagResultHref(projectId: number, jobId: number): string {
  return `/projects/${projectId}/tags?tab=custom-tags&customTagJob=${jobId}`;
}

/** 静的コンテンツ生成ジョブの「結果を見る」の遷移先(#1409)。サイト編集画面が `staticContentJob` の結果を表示する。 */
export function buildStaticContentResultHref(siteId: number, jobId: number): string {
  return `/sites/${siteId}/edit?staticContentJob=${jobId}`;
}

/**
 * タグデザイン生成ジョブの「結果を見る」の遷移先(#1409)。プロジェクト個別なら「組み込みタグのデザイン」タブ、
 * グローバル(`projectId` が null)ならグローバルタグデザイン画面が、`tagDesignJob` の結果を表示する。
 */
export function buildTagDesignResultHref(projectId: number | null, jobId: number): string {
  return projectId === null
    ? `/admin/tag-design?tagDesignJob=${jobId}`
    : `/projects/${projectId}/tags?tab=tag-design&tagDesignJob=${jobId}`;
}

/**
 * サイト自動構築ジョブの「結果を見る」の遷移先(#1696)。作成されたサイトの編集画面を開く。
 * 構築はリクエストにサイトIDを持たない(作る前なので)ため、完了したジョブの結果から読んだIDで組み立てる。
 */
export function buildSiteProvisioningResultHref(siteId: number): string {
  return `/sites/${siteId}/edit`;
}

/** 完了したサイト自動構築ジョブの結果(`result_payload`)から、作成されたサイトのIDを読む。読めなければ null。 */
export function readSiteId(resultPayload: string | null): number | null {
  return readNumberField(resultPayload, "siteId");
}

/** 完了した画像生成ジョブの結果(`result_payload`)から、生成された画像のIDを読む。読めなければ空。 */
export function readImageIds(resultPayload: string | null): number[] {
  if (!resultPayload) return [];
  try {
    const parsed: unknown = JSON.parse(resultPayload);
    const ids = (parsed as { imageIds?: unknown } | null)?.imageIds;
    return Array.isArray(ids) ? ids.filter((id): id is number => typeof id === "number") : [];
  } catch {
    return [];
  }
}

/** 失敗したジョブの結果(`result_payload` の `{"error": ...}`)から、失敗理由を短く読む。読めなければ null。 */
export function readFailureReason(resultPayload: string | null): string | null {
  if (!resultPayload) return null;
  try {
    const parsed: unknown = JSON.parse(resultPayload);
    const error = (parsed as { error?: unknown } | null)?.error;
    if (typeof error !== "string") return null;
    const text = error.trim();
    if (!text) return null;
    return text.length > FAILURE_REASON_MAX_LENGTH ? `${text.slice(0, FAILURE_REASON_MAX_LENGTH - 1)}…` : text;
  } catch {
    return null;
  }
}
