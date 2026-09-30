import { REVIEW_STEPS, ReviewStepDefinition, StepFinding } from '../proofreadLogic';
import {
  buildChecklistItems,
  computeBodyHash,
  computeFindingId,
  countUnresolvedItems,
  groupChecklistItemsByStep,
  isSnapshotCurrent,
  mergeChecklistState,
  ReviewChecklistItem,
  setChecklistItemStatus,
} from '../reviewChecklistLogic';

/**
 * 指摘チェックリストの純ロジック(issue #1216)。
 *
 * - 「ステップキー + 引用文 + 指摘内容」から本文中の出現位置を含まないIDを導く(AC4)。
 * - レビュー結果(StepFinding[])からチェックリスト項目を組み立て、前回の対応状態を引き継ぐ(AC4)。
 * - 見つからなくなった指摘は、位置解決済みのfindingsに含まれない時点で自然に除外される(AC5)。
 */

function step(key: ReviewStepDefinition['key']): ReviewStepDefinition {
  const found = REVIEW_STEPS.find((s) => s.key === key);
  if (!found) throw new Error(`未知のステップ: ${key}`);
  return found;
}

function finding(overrides: Partial<{ stepKey: ReviewStepDefinition['key']; originalText: string; message: string; suggestion: string | null }>): StepFinding {
  const stepDef = step(overrides.stepKey ?? 'PROOFREADING');
  return {
    step: stepDef,
    suggestion: {
      originalText: overrides.originalText ?? '本文の一部',
      message: overrides.message ?? '誤字があります',
      suggestion: overrides.suggestion ?? null,
      sources: [],
    },
    startOffset: 0,
    endOffset: 0,
  };
}

describe('computeFindingId', () => {
  it('ステップキー・引用文・指摘内容が同じなら同じIDになる', () => {
    const a = computeFindingId('PROOFREADING', '本文の一部', '誤字があります');
    const b = computeFindingId('PROOFREADING', '本文の一部', '誤字があります');
    expect(a).toBe(b);
  });

  it('いずれか1つでも違えば異なるIDになる', () => {
    const base = computeFindingId('PROOFREADING', '本文の一部', '誤字があります');
    expect(computeFindingId('FACT_CHECK', '本文の一部', '誤字があります')).not.toBe(base);
    expect(computeFindingId('PROOFREADING', '別の一部', '誤字があります')).not.toBe(base);
    expect(computeFindingId('PROOFREADING', '本文の一部', '別の指摘')).not.toBe(base);
  });
});

describe('computeBodyHash', () => {
  it('同じ本文からは同じハッシュを計算する', () => {
    expect(computeBodyHash('こんにちは')).toBe(computeBodyHash('こんにちは'));
  });

  it('本文が違えばハッシュも変わる', () => {
    expect(computeBodyHash('こんにちは')).not.toBe(computeBodyHash('さようなら'));
  });
});

describe('buildChecklistItems', () => {
  it('前回の項目が無ければ全項目が未対応から始まる', () => {
    const items = buildChecklistItems([finding({})], undefined);
    expect(items).toHaveLength(1);
    expect(items[0].status).toBe('unresolved');
  });

  it('ステップキー・引用文・指摘内容が一致する前回項目の対応状態を引き継ぐ', () => {
    const first = buildChecklistItems([finding({ originalText: 'AはBです' })], undefined);
    const fixedFirst = setChecklistItemStatus(first, first[0].id, 'fixed');

    // 加筆で位置がずれても(startOffset/endOffsetを変えても)、identityは変わらない。
    const rerun: StepFinding[] = [
      { ...finding({ originalText: 'AはBです' }), startOffset: 999, endOffset: 1005 },
    ];
    const second = buildChecklistItems(rerun, fixedFirst);

    expect(second).toHaveLength(1);
    expect(second[0].status).toBe('fixed');
  });

  it('本文から引用文が見つからなくなった指摘(findingsに含まれない)は結果に残らない', () => {
    const first = buildChecklistItems(
      [finding({ originalText: 'AはBです' }), finding({ originalText: 'CはDです' })],
      undefined
    );

    // 2つ目の引用文が本文から削除され、位置解決済みのfindingsには1つ目しか含まれない状況を再現する。
    const rerun = [finding({ originalText: 'AはBです' })];
    const second = buildChecklistItems(rerun, first);

    expect(second).toHaveLength(1);
    expect(second[0].originalText).toBe('AはBです');
  });

  it('新規の指摘は前回に無くても未対応から始まる', () => {
    const first = buildChecklistItems([finding({ originalText: 'AはBです' })], undefined);
    const rerun = [finding({ originalText: 'AはBです' }), finding({ originalText: '新しい指摘' })];
    const second = buildChecklistItems(rerun, first);

    expect(second).toHaveLength(2);
    expect(second.find((i) => i.originalText === '新しい指摘')?.status).toBe('unresolved');
  });
});

describe('mergeChecklistState', () => {
  it('本文ハッシュと項目をまとめて1つの状態にする', () => {
    const state = mergeChecklistState(undefined, [finding({})], 'hash-1');
    expect(state.bodyHash).toBe('hash-1');
    expect(state.items).toHaveLength(1);
  });
});

describe('setChecklistItemStatus', () => {
  it('該当IDの項目だけ対応状態を変更する', () => {
    const items = buildChecklistItems(
      [finding({ originalText: 'X' }), finding({ originalText: 'Y' })],
      undefined
    );
    const updated = setChecklistItemStatus(items, items[0].id, 'skipped');

    expect(updated[0].status).toBe('skipped');
    expect(updated[1].status).toBe('unresolved');
    // 不変更新であること(元の配列は変わらない)。
    expect(items[0].status).toBe('unresolved');
  });

  it('該当するIDが無ければ何も変更しない', () => {
    const items = buildChecklistItems([finding({})], undefined);
    const updated = setChecklistItemStatus(items, 'no-such-id', 'fixed');
    expect(updated).toEqual(items);
  });
});

describe('groupChecklistItemsByStep', () => {
  it('レビュー実行順(ステップ定義順)にグループ化する', () => {
    const items: ReviewChecklistItem[] = [
      { id: '1', stepKey: 'STYLE', stepLabel: '文体チェック', originalText: 'a', message: 'm', suggestion: null, status: 'unresolved' },
      { id: '2', stepKey: 'JAPANESE', stepLabel: '日本語チェック', originalText: 'b', message: 'm', suggestion: null, status: 'unresolved' },
    ];

    const groups = groupChecklistItemsByStep(items);

    expect(groups.map((g) => g.stepKey)).toEqual(['JAPANESE', 'STYLE']);
  });

  it('項目が1件も無いステップはグループに含めない', () => {
    const items: ReviewChecklistItem[] = [
      { id: '1', stepKey: 'STYLE', stepLabel: '文体チェック', originalText: 'a', message: 'm', suggestion: null, status: 'unresolved' },
    ];

    const groups = groupChecklistItemsByStep(items);

    expect(groups).toHaveLength(1);
    expect(groups[0].stepKey).toBe('STYLE');
  });
});

/**
 * issue #1217: Publish直前のレビューが使う判定。「本文が変わっていないか」は#1226(プレビュー直前)も
 * 同じものを使うため、スナップショットを持つこのモジュール側に置く。
 */
describe('countUnresolvedItems', () => {
  function item(status: ReviewChecklistItem['status'], message: string): ReviewChecklistItem {
    return { ...buildChecklistItems([finding({ message })], undefined)[0], status };
  }

  it('未対応の項目だけを数える(修正済みとスキップは数えない)', () => {
    const items = [item('unresolved', 'a'), item('fixed', 'b'), item('skipped', 'c'), item('unresolved', 'd')];
    expect(countUnresolvedItems(items)).toBe(2);
  });

  it('項目が無ければ0', () => {
    expect(countUnresolvedItems([])).toBe(0);
  });
});

describe('isSnapshotCurrent', () => {
  it('保持しているスナップショットのハッシュと本文のハッシュが一致すればtrue', () => {
    const state = { bodyHash: computeBodyHash('本文A'), items: [] };
    expect(isSnapshotCurrent(state, '本文A')).toBe(true);
  });

  it('本文が1文字でも変わっていればfalse', () => {
    const state = { bodyHash: computeBodyHash('本文A'), items: [] };
    expect(isSnapshotCurrent(state, '本文B')).toBe(false);
  });

  it('スナップショットが無い(未レビュー)ならfalse', () => {
    expect(isSnapshotCurrent(undefined, '本文A')).toBe(false);
  });
});
