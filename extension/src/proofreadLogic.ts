import { ProofreadIssue } from './schemas';

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
export interface LocatedProofreadIssue {
  issue: ProofreadIssue;
  startOffset: number;
  endOffset: number;
}

/**
 * AIが返した指摘一覧を、本文(content)中の実際の位置へ解決する。
 * originalTextでの検索は、直前の指摘の終端より後を優先することで、同じ表現が複数回
 * 登場する場合でもAIが指摘した順番通りの箇所に対応付けられるようにする。見つからない場合は
 * 本文全体を再検索し、それでも見つからない指摘(originalTextの引用が不正確等)は除外する。
 */
export function locateContentIssues(
  content: string,
  bodyOffset: number,
  issues: ProofreadIssue[]
): LocatedProofreadIssue[] {
  const located: LocatedProofreadIssue[] = [];
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
