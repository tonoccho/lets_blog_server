import type * as vscode from 'vscode';
import { ReviewChecklistStore } from '../reviewChecklistStore';
import { REVIEW_STEPS, StepFinding } from '../proofreadLogic';

/**
 * 指摘チェックリストの永続化(issue #1216)。`context.workspaceState`へ記事ファイル(documentKey)
 * ごとに保存する。VSCode再起動をテストで直接は再現できないため、`workspaceState`を跨いだ
 * 別インスタンスからも同じ値が読めることでその代わりとする(config.test.tsと同じやり方)。
 */

function createContext(): vscode.ExtensionContext {
  const values = new Map<string, unknown>();
  return {
    workspaceState: {
      get: (key: string) => values.get(key),
      update: (key: string, value: unknown) => {
        values.set(key, value);
        return Promise.resolve();
      },
    },
  } as unknown as vscode.ExtensionContext;
}

function finding(originalText: string, message = '誤字があります'): StepFinding {
  return {
    step: REVIEW_STEPS[1],
    suggestion: { originalText, message, suggestion: null, sources: [] },
    startOffset: 0,
    endOffset: 0,
  };
}

describe('ReviewChecklistStore', () => {
  it('初回は未保存(undefined)を返す', () => {
    const store = new ReviewChecklistStore(createContext());
    expect(store.get('doc-1')).toBeUndefined();
  });

  it('recordReviewで保存した内容をgetで読み返せる', async () => {
    const store = new ReviewChecklistStore(createContext());
    const state = await store.recordReview('doc-1', [finding('AはBです')], 'hash-1');

    expect(state.bodyHash).toBe('hash-1');
    expect(store.get('doc-1')).toEqual(state);
  });

  it('記事ファイル(documentKey)が異なれば別々に保存される', async () => {
    const context = createContext();
    const store = new ReviewChecklistStore(context);
    await store.recordReview('doc-1', [finding('AはBです')], 'hash-1');
    await store.recordReview('doc-2', [finding('CはDです')], 'hash-2');

    expect(store.get('doc-1')?.bodyHash).toBe('hash-1');
    expect(store.get('doc-2')?.bodyHash).toBe('hash-2');
  });

  it('再起動を模した別インスタンスからも同じ状態が読める', async () => {
    const context = createContext();
    const first = new ReviewChecklistStore(context);
    await first.recordReview('doc-1', [finding('AはBです')], 'hash-1');

    const second = new ReviewChecklistStore(context);
    expect(second.get('doc-1')?.bodyHash).toBe('hash-1');
  });

  it('setStatusで指定した項目の対応状態を更新して保存する', async () => {
    const store = new ReviewChecklistStore(createContext());
    const state = await store.recordReview('doc-1', [finding('AはBです')], 'hash-1');

    const updated = await store.setStatus('doc-1', state.items[0].id, 'fixed');

    expect(updated?.items[0].status).toBe('fixed');
    expect(store.get('doc-1')?.items[0].status).toBe('fixed');
  });

  it('永続化済みの状態が無いdocumentKeyへのsetStatusは何もしない', async () => {
    const store = new ReviewChecklistStore(createContext());
    const updated = await store.setStatus('doc-1', 'no-such-id', 'fixed');
    expect(updated).toBeUndefined();
  });

  it('レビュー再実行では前回の対応状態を引き継ぐ', async () => {
    const store = new ReviewChecklistStore(createContext());
    const first = await store.recordReview('doc-1', [finding('AはBです')], 'hash-1');
    await store.setStatus('doc-1', first.items[0].id, 'skipped');

    const second = await store.recordReview('doc-1', [finding('AはBです')], 'hash-2');

    expect(second.items[0].status).toBe('skipped');
    expect(second.bodyHash).toBe('hash-2');
  });

  it('スキップされたステップを記録し、別インスタンス(再起動相当)からも読める(issue #1545 AC3)', async () => {
    const context = createContext();
    await new ReviewChecklistStore(context).recordReview('doc-1', [], 'h', [
      { step: REVIEW_STEPS[1], reason: '本文が短いため' },
    ]);

    const restored = new ReviewChecklistStore(context).get('doc-1');
    expect(restored?.skippedSteps).toEqual([
      { stepKey: REVIEW_STEPS[1].key, stepLabel: REVIEW_STEPS[1].label, reason: '本文が短いため' },
    ]);
  });

  it('スキップの無いレビューを再実行すると、前回のスキップ表示は消える(issue #1545 AC3)', async () => {
    const store = new ReviewChecklistStore(createContext());
    await store.recordReview('doc-1', [], 'h1', [{ step: REVIEW_STEPS[1], reason: 'r' }]);
    await store.recordReview('doc-1', [], 'h2');
    expect(store.get('doc-1')?.skippedSteps).toEqual([]);
  });

  it('setStatusはスキップ情報を保ったまま対応状態だけを更新する(issue #1545)', async () => {
    const store = new ReviewChecklistStore(createContext());
    const state = await store.recordReview('doc-1', [finding('AはBです')], 'h', [{ step: REVIEW_STEPS[2], reason: 'r' }]);
    const updated = await store.setStatus('doc-1', state.items[0].id, 'fixed');
    expect(updated?.skippedSteps).toHaveLength(1);
    expect(updated?.items[0].status).toBe('fixed');
  });
});
