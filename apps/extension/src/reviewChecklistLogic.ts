import * as crypto from 'crypto';
import { REVIEW_STEPS, ReviewStepDefinition, ReviewStepKey, StepFinding } from './proofreadLogic';

/**
 * 指摘チェックリスト(issue #1216)のうち、vscode APIに依存しない純粋なロジック。
 *
 * 対応状態の永続化(reviewChecklistStore.ts)とWebviewパネル(reviewChecklistPanel.ts)から
 * 共通で使う「識別・組み立て・グループ化」をここへ分離し、単体テストで押さえる。
 */

/** 指摘1件に対する対応状態。 */
export type ReviewChecklistStatus = 'unresolved' | 'fixed' | 'skipped';

/** 新規の指摘の既定の対応状態。 */
export const DEFAULT_REVIEW_CHECKLIST_STATUS: ReviewChecklistStatus = 'unresolved';

/** チェックリスト1項目。表示に必要な情報と現在の対応状態を持つ。 */
export interface ReviewChecklistItem {
  /** 「ステップキー + 引用文 + 指摘内容」から導く安定ID。本文中の出現位置は含まない。 */
  id: string;
  stepKey: ReviewStepKey;
  stepLabel: string;
  originalText: string;
  message: string;
  suggestion: string | null;
  status: ReviewChecklistStatus;
}

/** ステップ別にまとめたチェックリスト1グループ。 */
export interface ReviewChecklistGroup {
  stepKey: ReviewStepKey;
  stepLabel: string;
  items: ReviewChecklistItem[];
}

/** 記事1件分の、永続化するチェックリスト状態。 */
export interface ReviewChecklistDocumentState {
  /**
   * 判定の基準となった本文のスナップショットのハッシュ。
   * issue #1217 / #1226が「本文が未変更なら再実行しない」の判定に使う(判定は`isSnapshotCurrent`)。
   */
  bodyHash: string;
  items: ReviewChecklistItem[];
}

/**
 * 「ステップキー + 引用文 + 指摘内容」から安定IDを導く。本文中の出現位置を含まないため、
 * 指摘箇所より前方に加筆されて位置がずれても、同じ指摘には同じIDが振られる(issue #1216)。
 */
export function computeFindingId(stepKey: string, originalText: string, message: string): string {
  return crypto
    .createHash('sha256')
    .update(`${stepKey}\u0000${originalText}\u0000${message}`)
    .digest('hex');
}

/**
 * 本文のスナップショットのハッシュ。本文そのものではなくハッシュを保持することで、
 * workspaceStateの肥大化を避ける(内容の突き合わせにはハッシュの一致で十分)。
 */
export function computeBodyHash(content: string): string {
  return crypto.createHash('sha256').update(content).digest('hex');
}

/**
 * レビュー結果(本文中の位置解決済みの指摘)から、チェックリスト項目を組み立てる。
 *
 * 引用文が本文から見つからなくなった指摘は、`runReviewSteps`(`locateContentIssues`)が
 * 位置解決の時点で既に除外しているため、findings自体に含まれない。したがって前回の項目のうち
 * 今回のfindingsに対応するものが無ければ、戻り値にも含めないことで自然に消える(issue #1216のAC5)。
 */
export function buildChecklistItems(
  findings: StepFinding[],
  previousItems: ReviewChecklistItem[] | undefined
): ReviewChecklistItem[] {
  const previousStatusById = new Map((previousItems ?? []).map((item) => [item.id, item.status]));
  return findings.map((finding) => {
    const id = computeFindingId(finding.step.key, finding.suggestion.originalText, finding.suggestion.message);
    return {
      id,
      stepKey: finding.step.key,
      stepLabel: finding.step.label,
      originalText: finding.suggestion.originalText,
      message: finding.suggestion.message,
      suggestion: finding.suggestion.suggestion,
      status: previousStatusById.get(id) ?? DEFAULT_REVIEW_CHECKLIST_STATUS,
    };
  });
}

/** レビュー1回分の結果を、前回の対応状態を引き継いだ永続化用の状態へまとめる。 */
export function mergeChecklistState(
  previous: ReviewChecklistDocumentState | undefined,
  findings: StepFinding[],
  bodyHash: string
): ReviewChecklistDocumentState {
  return { bodyHash, items: buildChecklistItems(findings, previous?.items) };
}

/** 対応状態を1件だけ更新した新しい配列を返す(不変更新)。該当IDが無ければ変更しない。 */
export function setChecklistItemStatus(
  items: ReviewChecklistItem[],
  id: string,
  status: ReviewChecklistStatus
): ReviewChecklistItem[] {
  return items.map((item) => (item.id === id ? { ...item, status } : item));
}

/**
 * ステップの定義順(レビュー実行順)に、項目を持つステップだけをグループ化する。
 * 校閲ステップがスキップされたことの一覧上での表示は#1225のスコープのため、
 * 0件のステップはここでは表示せずグループから外す。
 */
export function groupChecklistItemsByStep(items: ReviewChecklistItem[]): ReviewChecklistGroup[] {
  const groups: ReviewChecklistGroup[] = [];
  for (const step of REVIEW_STEPS as readonly ReviewStepDefinition[]) {
    const stepItems = items.filter((item) => item.stepKey === step.key);
    if (stepItems.length > 0) {
      groups.push({ stepKey: step.key, stepLabel: step.label, items: stepItems });
    }
  }
  return groups;
}

/**
 * 保持しているスナップショットが、いまの本文に対するものか(=本文が直近のレビュー以降変わっていないか)。
 * Publish直前(issue #1217)とプレビュー直前(issue #1226)が同じ判定を使うため、二重実装にならないよう
 * スナップショットを持つここに置く。判定は本文(`article.content`)だけで行い、front matterは見ない。
 */
export function isSnapshotCurrent(state: ReviewChecklistDocumentState | undefined, content: string): boolean {
  return state !== undefined && state.bodyHash === computeBodyHash(content);
}

/** 未対応の項目数。「修正済み」「スキップ」は数えない(issue #1217)。 */
export function countUnresolvedItems(items: ReviewChecklistItem[]): number {
  return items.filter((item) => item.status === 'unresolved').length;
}
