import { ReviewStepSuggestion } from './schemas';

/**
 * 校正チェック(issue #523)のうち、vscode APIに依存しない純粋なロジック。
 * front matterの各行位置の特定や、AIの指摘を本文中の位置へ解決する処理はテスト容易性のため
 * ここへ分離し、vscode.Range等への変換はproofreadDiagnostics.ts側で行う。
 */

const FRONT_MATTER_BLOCK_PATTERN = /^---\r?\n[\s\S]*?\r?\n---\r?\n?/;

/** front matterブロックの直後、本文が始まる文字位置。front matterが無い場合は0。 */
export function computeBodyOffset(rawText: string): number {
  const match = FRONT_MATTER_BLOCK_PATTERN.exec(rawText);
  return match ? match[0].length : 0;
}

/** 本文中で位置が特定できた指摘。開始・終了位置はrawText全体(front matter込み)に対するオフセット。 */
export interface LocatedProofreadIssue<T extends { originalText: string } = { originalText: string }> {
  issue: T;
  startOffset: number;
  endOffset: number;
}

/**
 * AIが返した指摘一覧を、本文(content)中の実際の位置へ解決する。
 * originalTextでの検索は、直前の指摘の終端より後を優先することで、同じ表現が複数回
 * 登場する場合でもAIが指摘した順番通りの箇所に対応付けられるようにする。見つからない場合は
 * 本文全体を再検索し、それでも見つからない指摘(originalTextの引用が不正確等)は除外する。
 */
export function locateContentIssues<T extends { originalText: string }>(
  content: string,
  bodyOffset: number,
  issues: T[]
): LocatedProofreadIssue<T>[] {
  const located: LocatedProofreadIssue<T>[] = [];
  let cursor = 0;
  for (const issue of issues) {
    if (!issue.originalText) continue;
    let index = content.indexOf(issue.originalText, cursor);
    if (index < 0) {
      index = content.indexOf(issue.originalText, 0);
    }
    if (index < 0) continue;
    const startOffset = bodyOffset + index;
    const endOffset = startOffset + issue.originalText.length;
    located.push({ issue, startOffset, endOffset });
    cursor = index + issue.originalText.length;
  }
  return located;
}

/** front matter中の1項目(key: ...)が書かれている行の位置。 */
export interface FrontMatterFieldLocation {
  line: number;
  startColumn: number;
  endColumn: number;
}

/**
 * front matter中のkey(例: "status", "categories", "publish_scheduled_at")が書かれている行を探す。
 * gray-matterはYAMLノードごとの位置情報を返さないため、診断の下線範囲としてはその行全体を使う。
 */
export function findFrontMatterFieldLine(rawText: string, key: string): FrontMatterFieldLocation | undefined {
  const bodyOffset = computeBodyOffset(rawText);
  if (bodyOffset === 0) return undefined;
  const lines = rawText.slice(0, bodyOffset).split(/\r?\n/);
  const pattern = new RegExp(`^${escapeRegExp(key)}\\s*:`);
  for (let i = 0; i < lines.length; i++) {
    if (pattern.test(lines[i])) {
      return { line: i, startColumn: 0, endColumn: lines[i].length };
    }
  }
  return undefined;
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * front matterのcategoriesのうち、サイトに実在しないものを返す。
 * existingCategoriesが空の場合はサイト未紐付けや取得失敗を意味し(issue #525のlistExistingTagsと同じ
 * フェイルセーフ契約)、全件を不正として扱ってしまわないようチェック自体を行わない。
 */
export function findInvalidCategories(categories: string[] | undefined, existingCategories: string[]): string[] {
  if (!categories || categories.length === 0 || existingCategories.length === 0) {
    return [];
  }
  const existingSet = new Set(existingCategories.map((c) => c.toLowerCase()));
  return categories.filter((c) => !existingSet.has(c.toLowerCase()));
}

/**
 * front matterのstatusが有効な値かどうか。validValuesが空の場合は取得失敗を意味するため、
 * 誤検知を避けるためチェックをスキップ(有効とみなす)する。
 */
export function isValidStatus(status: string | undefined, validValues: string[]): boolean {
  if (!status) return true;
  if (validValues.length === 0) return true;
  return validValues.some((v) => v.toLowerCase() === status.toLowerCase());
}

export type ReviewStepKey = 'JAPANESE' | 'PROOFREADING' | 'FACT_CHECK' | 'READER_PERSPECTIVE' | 'STYLE';

/** レビューの1ステップ。colorIdはステップ別のアンダーライン色(VS CodeのThemeColor ID)。 */
export interface ReviewStepDefinition {
  key: ReviewStepKey;
  label: string;
  colorId: string;
}

/** レビュー5ステップ。実行順そのもの(issue #1215)。色はライト/ダーク双方で読めるテーマ色を使う。 */
export const REVIEW_STEPS: readonly ReviewStepDefinition[] = [
  { key: 'JAPANESE', label: '日本語チェック', colorId: 'charts.red' },
  { key: 'PROOFREADING', label: '校正チェック', colorId: 'charts.orange' },
  { key: 'FACT_CHECK', label: '校閲', colorId: 'charts.blue' },
  { key: 'READER_PERSPECTIVE', label: '読者視点でのチェック', colorId: 'charts.green' },
  { key: 'STYLE', label: '文体チェック', colorId: 'charts.purple' },
];

/** 進捗表示の文言。indexは0始まり。 */
export function reviewProgressMessage(index: number, total: number, label: string): string {
  return `${label} (${index + 1}/${total})`;
}

/** 指摘箇所のホバーに出すMarkdown。ステップ名・指摘内容と、あれば提案・出典を含める。 */
export function buildFindingHover(
  label: string,
  message: string,
  suggestion: string | null | undefined,
  sources: { title?: string; url: string }[]
): string {
  const lines = [`**${label}**`, '', message];
  if (suggestion) lines.push('', `提案: ${suggestion}`);
  for (const source of sources) {
    lines.push('', source.title ? `[${source.title}](${source.url})` : source.url);
  }
  return lines.join('\n');
}

/** 位置が特定できた、あるステップの指摘。 */
export interface StepFinding {
  step: ReviewStepDefinition;
  suggestion: ReviewStepSuggestion;
  startOffset: number;
  endOffset: number;
}

/** 中断されたレビューが投げる例外のメッセージ。呼び出し側は中断と失敗を区別するため、この文言ではなくsignalを見る。 */
export const REVIEW_CANCELLED_MESSAGE = 'レビューが中断されました';

/** 1ステップぶんのAPI応答。skippedはサーバが実行しなかった(失敗ではない)ことを示す。 */
export interface StepFetchResult {
  suggestions: ReviewStepSuggestion[];
  skipped: boolean;
  skipReason?: string;
}

export interface StepFailure {
  step: ReviewStepDefinition;
  error: unknown;
}

export interface StepSkip {
  step: ReviewStepDefinition;
  reason: string;
}

export interface ReviewRunResult {
  findings: StepFinding[];
  /** サーバエラー等で実行できなかったステップ。他のステップの結果には影響しない。 */
  failures: StepFailure[];
  /** サーバが理由つきで実行を見送ったステップ。指摘0件とは区別する。 */
  skipped: StepSkip[];
}

export interface RunReviewStepsOptions {
  content: string;
  bodyOffset: number;
  fetchStep: (key: ReviewStepKey, text: string) => Promise<StepFetchResult>;
  onStep: (step: ReviewStepDefinition, index: number, total: number) => void;
  signal?: AbortSignal;
}

const DEFAULT_SKIP_REASON = '理由は報告されませんでした';

/**
 * 5ステップを定義順に1つずつ実行し、全ステップの指摘を本文中の位置へ解決して集約する。
 * ステップごとに利用者の操作は待たない。signalが中断されたら次のステップへ進まず例外を投げる。
 * 中断ではないステップの失敗は記録して次のステップへ進み、スキップは理由つきで別に集める(issue #1224)。
 */
export async function runReviewSteps(options: RunReviewStepsOptions): Promise<ReviewRunResult> {
  const result: ReviewRunResult = { findings: [], failures: [], skipped: [] };
  for (let index = 0; index < REVIEW_STEPS.length; index++) {
    if (options.signal?.aborted) {
      throw new Error(REVIEW_CANCELLED_MESSAGE);
    }
    const step = REVIEW_STEPS[index];
    options.onStep(step, index, REVIEW_STEPS.length);
    let fetched: StepFetchResult;
    try {
      fetched = await options.fetchStep(step.key, options.content);
    } catch (error) {
      if (options.signal?.aborted) {
        throw new Error(REVIEW_CANCELLED_MESSAGE);
      }
      result.failures.push({ step, error });
      continue;
    }
    if (fetched.skipped) {
      result.skipped.push({ step, reason: fetched.skipReason || DEFAULT_SKIP_REASON });
    }
    for (const located of locateContentIssues(options.content, options.bodyOffset, fetched.suggestions)) {
      result.findings.push({ step, suggestion: located.issue, startOffset: located.startOffset, endOffset: located.endOffset });
    }
  }
  return result;
}

/** 失敗・スキップを利用者へ知らせる文言。失敗は警告、スキップは(失敗ではないため)情報として分ける。 */
export function reviewOutcomeMessages(result: ReviewRunResult): { warning?: string; info?: string } {
  const messages: { warning?: string; info?: string } = {};
  if (result.failures.length > 0) {
    const detail = result.failures.map((f) => `「${f.step.label}」(${errorText(f.error)})`).join('、');
    messages.warning = `次のステップが失敗しました: ${detail}。他のステップの結果は表示しています。`;
  }
  if (result.skipped.length > 0) {
    const detail = result.skipped.map((s) => `「${s.step.label}」(${s.reason})`).join('、');
    messages.info = `次のステップはスキップされました(指摘0件ではありません): ${detail}`;
  }
  return messages;
}

function errorText(error: unknown): string {
  return String(error instanceof Error ? error.message : error);
}
