import { getGenerationJob, type EmbedTagType, type StaticContentType } from "@/lib/apiClient";

/**
 * LLM生成ジョブ(カスタムタグ・静的コンテンツ・タグデザイン、issue #1409)の `result_payload` の読み取りと、
 * 「結果を見る」の遷移先画面がジョブIDからその結果を引く処理。
 *
 * 生成テキストはジョブの結果にだけ置かれ、各機能の保存先には書かれない。画面は結果をここから読み、
 * 利用者が「保存」を押したときに初めて既存の保存APIへ渡す。結果が読めない(失敗・別の種別・壊れた内容)ときは
 * null を返し、画面は結果なしで描画する。
 */

export const STATIC_CONTENT_TYPES: readonly StaticContentType[] = ["PRIVACY_POLICY", "OPERATOR_INFO", "TERMS_OF_SERVICE"];
const EMBED_TAG_TYPES: readonly EmbedTagType[] = ["TOC", "BLOGCARD", "AMAZON"];

export interface CustomTagJobResult {
  tagName: string;
  description: string | null;
  /** プロジェクトに紐付かない(グローバル)タグなら null。 */
  projectId: number | null;
  htmlTemplate: string;
  cssContent: string;
}

export interface StaticContentJobResult {
  siteId: number;
  contentType: StaticContentType;
  body: string;
}

export interface TagDesignJobResult {
  /** グローバル既定なら null。 */
  projectId: number | null;
  tagType: EmbedTagType;
  /** AIが構造変更不要と判断したときは空文字(その場合は現在のHTMLテンプレートを維持する)。 */
  htmlTemplate: string;
  cssContent: string;
}

function readObject(payload: string | null): Record<string, unknown> | null {
  if (!payload) return null;
  try {
    const parsed: unknown = JSON.parse(payload);
    return parsed !== null && typeof parsed === "object" ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

const asString = (value: unknown): string | null => (typeof value === "string" ? value : null);
const asNumber = (value: unknown): number | null => (typeof value === "number" ? value : null);

export function readCustomTagJobResult(resultPayload: string | null): CustomTagJobResult | null {
  const result = readObject(resultPayload);
  const tagName = asString(result?.tagName);
  const htmlTemplate = asString(result?.htmlTemplate);
  if (result === null || tagName === null || htmlTemplate === null) return null;
  return {
    tagName,
    description: asString(result.description),
    projectId: asNumber(result.projectId),
    htmlTemplate,
    cssContent: asString(result.cssContent) ?? "",
  };
}

export function readStaticContentJobResult(resultPayload: string | null): StaticContentJobResult | null {
  const result = readObject(resultPayload);
  const siteId = asNumber(result?.siteId);
  const contentType = STATIC_CONTENT_TYPES.find((type) => type === result?.contentType);
  const body = asString(result?.body);
  if (siteId === null || contentType === undefined || body === null) return null;
  return { siteId, contentType, body };
}

export function readTagDesignJobResult(resultPayload: string | null): TagDesignJobResult | null {
  const result = readObject(resultPayload);
  const tagType = EMBED_TAG_TYPES.find((type) => type === result?.tagType);
  const cssContent = asString(result?.cssContent);
  if (result === null || tagType === undefined || cssContent === null) return null;
  return {
    projectId: asNumber(result.projectId),
    tagType,
    htmlTemplate: asString(result.htmlTemplate) ?? "",
    cssContent,
  };
}

/**
 * 「結果を見る」の遷移先画面が、クエリのジョブIDからその結果を引く。ジョブの所有者による絞り込みは
 * API 側(#1406)なので、他人のジョブ・存在しないジョブは読めず null になる。完了していないジョブ、
 * 別の種別のジョブ、結果が読めないジョブも null。
 */
export async function loadLlmJobResult<T>(
  rawJobId: string | undefined,
  type: string,
  read: (resultPayload: string | null) => T | null
): Promise<(T & { jobId: number }) | null> {
  const jobId = Number(rawJobId);
  if (!Number.isInteger(jobId) || jobId <= 0) return null;
  try {
    const job = await getGenerationJob(jobId);
    if (job.type !== type || job.status !== "done") return null;
    const result = read(job.resultPayload);
    return result === null ? null : { ...result, jobId };
  } catch {
    return null;
  }
}
