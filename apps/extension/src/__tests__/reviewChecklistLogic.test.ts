import { REVIEW_STEPS, ReviewStepDefinition, StepFinding } from '../proofreadLogic';
import {
  buildChecklistItems,
  buildChecklistView,
  computeBodyHash,
  computeFindingId,
  findOriginalTextOffset,
  JUMP_NOT_FOUND_MESSAGE,
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

  it('スキップされたステップを、ステップ名と理由つきで記録する(issue #1545 AC1)', () => {
    const state = mergeChecklistState(undefined, [], 'h', [
      { step: step('PROOFREADING'), reason: '本文が短いため' },
    ]);
    expect(state.skippedSteps).toEqual([
      { stepKey: 'PROOFREADING', stepLabel: step('PROOFREADING').label, reason: '本文が短いため' },
    ]);
  });

  it('スキップが無ければ空配列を記録し、前回のスキップは持ち越さない(issue #1545 AC3)', () => {
    const first = mergeChecklistState(undefined, [], 'h1', [{ step: step('PROOFREADING'), reason: 'r' }]);
    const second = mergeChecklistState(first, [], 'h2');
    expect(second.skippedSteps).toEqual([]);
  });

  it('スキップ情報を持たない既存の状態から再実行しても、対応状態は引き継がれる(issue #1545 AC4)', () => {
    const legacyItems = buildChecklistItems([finding({ originalText: 'X' })], undefined);
    legacyItems[0].status = 'fixed';
    const legacy = { bodyHash: 'old', items: legacyItems };
    const state = mergeChecklistState(legacy, [finding({ originalText: 'X' })], 'new');
    expect(state.items[0].status).toBe('fixed');
    expect(state.skippedSteps).toEqual([]);
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

/**
 * 指摘箇所へのジャンプ(issue #1225)。クリック時に現在の本文(front matterを除く)から
 * originalTextを再探索する。オフセットは永続化しない。
 */
describe('findOriginalTextOffset (issue #1225)', () => {
  it('本文中の最初の一致位置(rawText全体に対するオフセット)を返す', () => {
    const raw = 'AはBです。CはDです。';
    expect(findOriginalTextOffset(raw, 'CはD')).toBe(raw.indexOf('CはD'));
  });

  it('同じ文字列が複数あっても最初の一致を返す', () => {
    const raw = '赤い。青い。赤い。';
    expect(findOriginalTextOffset(raw, '赤い')).toBe(0);
  });

  it('front matterに同じ文字列があっても、本文側の一致を返す', () => {
    const raw = '---\ntitle: 重複語\n---\n本文に重複語があります';
    expect(findOriginalTextOffset(raw, '重複語')).toBe(raw.lastIndexOf('重複語'));
  });

  it('front matterにしか無い文字列は見つからない(undefined)', () => {
    const raw = '---\ntitle: 題名だけ\n---\n本文です';
    expect(findOriginalTextOffset(raw, '題名だけ')).toBeUndefined();
  });

  it('レビュー後に指摘箇所より前へ加筆されても、ずれた後の位置を返す', () => {
    const before = '前文。対象の一文。';
    const after = '加筆した段落です。\n' + before;
    expect(findOriginalTextOffset(after, '対象の一文')).toBe(after.indexOf('対象の一文'));
    expect(findOriginalTextOffset(after, '対象の一文')).not.toBe(findOriginalTextOffset(before, '対象の一文'));
  });

  it('本文に無ければundefined', () => {
    expect(findOriginalTextOffset('本文です', '存在しない')).toBeUndefined();
  });

  it('空の引用文は一致として扱わない(undefined)', () => {
    expect(findOriginalTextOffset('本文です', '')).toBeUndefined();
  });

  it('見つからない場合の表示文言は「本文に見つかりません」', () => {
    expect(JUMP_NOT_FOUND_MESSAGE).toBe('本文に見つかりません');
  });
});

describe('buildChecklistView (issue #1225)', () => {
  const item = (id: string, status: ReviewChecklistItem['status']): ReviewChecklistItem => ({
    id,
    stepKey: 'PROOFREADING',
    stepLabel: '校正チェック',
    originalText: id,
    message: 'm',
    suggestion: null,
    status,
  });

  it('未対応の件数を数え、修正済み・スキップは数えない(AC3)', () => {
    const view = buildChecklistView([item('a', 'unresolved'), item('b', 'fixed'), item('c', 'skipped'), item('d', 'unresolved')]);
    expect(view.unresolvedCount).toBe(2);
    expect(view.recorded).toBe(true);
    expect(view.isEmpty).toBe(false);
    expect(view.groups).toHaveLength(1);
  });

  it('対応状態を変えると、件数が追随する(AC4)', () => {
    const items = [item('a', 'unresolved'), item('b', 'unresolved')];
    expect(buildChecklistView(items).unresolvedCount).toBe(2);
    expect(buildChecklistView(setChecklistItemStatus(items, 'a', 'fixed')).unresolvedCount).toBe(1);
  });

  it('レビュー結果が記録済みで指摘0件なら、空である(AC5)', () => {
    const view = buildChecklistView([]);
    expect(view).toEqual({ groups: [], recorded: true, unresolvedCount: 0, isEmpty: true, skippedSteps: [] });
  });

  it('永続化状態が無い(レビュー未実行)なら、空表示にも件数表示にもしない', () => {
    const view = buildChecklistView(undefined);
    expect(view).toEqual({ groups: [], recorded: false, unresolvedCount: 0, isEmpty: false, skippedSteps: [] });
  });

  it('スキップされたステップはビューへそのまま渡り、指摘0件(isEmpty)とは別に保持される(issue #1545 AC1/AC2)', () => {
    const skippedSteps = [{ stepKey: 'PROOFREADING' as const, stepLabel: '校正チェック', reason: '本文が短いため' }];
    const view = buildChecklistView([], skippedSteps);
    expect(view.skippedSteps).toEqual(skippedSteps);
    expect(view.isEmpty).toBe(true);
    expect(buildChecklistView([], undefined).skippedSteps).toEqual([]);
  });
});
