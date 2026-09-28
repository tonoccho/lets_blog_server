import * as path from 'path';
import * as vscode from 'vscode';
import { lastCreatedWebviewPanel, resetMocks } from '../__mocks__/vscode';
import { ReviewChecklistPanel } from '../reviewChecklistPanel';
import { ReviewChecklistStore } from '../reviewChecklistStore';
import { REVIEW_STEPS, StepFinding } from '../proofreadLogic';

/**
 * 指摘チェックリストパネル(issue #1216)。AC1(別タブへステップ別に表示)・AC2(状態変更をWebviewへ反映)を、
 * Webview境界のメッセージで検証する。実際のクリック等のUI操作は
 * apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.mdの担当。
 */

let extensionRoot: string;

function createContextWithWorkspaceState(): vscode.ExtensionContext {
  const values = new Map<string, unknown>();
  return {
    extensionUri: `file://${extensionRoot}`,
    workspaceState: {
      get: (key: string) => values.get(key),
      update: (key: string, value: unknown) => {
        values.set(key, value);
        return Promise.resolve();
      },
    },
  } as unknown as vscode.ExtensionContext;
}

function finding(originalText: string): StepFinding {
  return {
    step: REVIEW_STEPS[1],
    suggestion: { originalText, message: '誤字があります', suggestion: null, sources: [] },
    startOffset: 0,
    endOffset: 0,
  };
}

async function send(message: unknown): Promise<void> {
  lastCreatedWebviewPanel?.webview.postMessageToExtension(message);
  await new Promise((resolve) => setImmediate(resolve));
}

function posted(): { command: string; payload: unknown }[] {
  return (lastCreatedWebviewPanel?.webview.posted ?? []) as { command: string; payload: unknown }[];
}

function lastChecklistPayload(): { groups: { stepKey: string; stepLabel: string; items: unknown[] }[] } {
  const found = posted().filter((m) => m.command === 'checklist');
  if (found.length === 0) throw new Error(`checklist が送られていません: ${JSON.stringify(posted())}`);
  return found[found.length - 1].payload as never;
}

beforeAll(() => {
  extensionRoot = path.resolve(__dirname, '..', '..');
});

afterEach(() => {
  lastCreatedWebviewPanel?.fireDispose();
  resetMocks();
});

describe('ReviewChecklistPanel', () => {
  it('showに渡した記事の永続化済みチェックリストを、ステップ別のグループとしてWebviewへ送る(AC1)', async () => {
    const context = createContextWithWorkspaceState();
    const store = new ReviewChecklistStore(context);
    await store.recordReview('doc-1', [finding('AはBです')], 'hash-1');

    const panel = ReviewChecklistPanel.createOrShow(context, store);
    panel.show('doc-1');

    const payload = lastChecklistPayload();
    expect(payload.groups).toHaveLength(1);
    expect(payload.groups[0].stepKey).toBe('PROOFREADING');
    expect(payload.groups[0].items).toHaveLength(1);
  });

  it('setStatusを受け取ると対応状態を永続化し、更新後のチェックリストを送り返す(AC2)', async () => {
    const context = createContextWithWorkspaceState();
    const store = new ReviewChecklistStore(context);
    const state = await store.recordReview('doc-1', [finding('AはBです')], 'hash-1');

    const panel = ReviewChecklistPanel.createOrShow(context, store);
    panel.show('doc-1');

    await send({ command: 'setStatus', id: state.items[0].id, status: 'fixed' });

    const payload = lastChecklistPayload();
    expect((payload.groups[0].items[0] as { status: string }).status).toBe('fixed');
    expect(store.get('doc-1')?.items[0].status).toBe('fixed');
  });

  it('refreshIfShowingは、表示中の記事と一致する場合だけ表示を更新する', async () => {
    const context = createContextWithWorkspaceState();
    const store = new ReviewChecklistStore(context);

    const panel = ReviewChecklistPanel.createOrShow(context, store);
    panel.show('doc-1');

    ReviewChecklistPanel.refreshIfShowing('doc-2', [
      {
        id: 'x',
        stepKey: 'PROOFREADING',
        stepLabel: '校正チェック',
        originalText: '別記事の指摘',
        message: 'm',
        suggestion: null,
        status: 'unresolved',
      },
    ]);
    expect(lastChecklistPayload().groups).toHaveLength(0);

    ReviewChecklistPanel.refreshIfShowing('doc-1', [
      {
        id: 'y',
        stepKey: 'PROOFREADING',
        stepLabel: '校正チェック',
        originalText: 'この記事の指摘',
        message: 'm',
        suggestion: null,
        status: 'unresolved',
      },
    ]);
    expect(lastChecklistPayload().groups[0].items).toHaveLength(1);
  });

  it('showを呼ぶ前にsetStatusを受け取っても何もしない(documentKey未設定)', async () => {
    const context = createContextWithWorkspaceState();
    const store = new ReviewChecklistStore(context);
    ReviewChecklistPanel.createOrShow(context, store);

    await send({ command: 'setStatus', id: 'no-such-id', status: 'fixed' });

    expect(posted().filter((m) => m.command === 'checklist')).toEqual([]);
  });

  it('永続化済みの状態が無い記事へのsetStatusは、表示を更新しない', async () => {
    const context = createContextWithWorkspaceState();
    const store = new ReviewChecklistStore(context);

    const panel = ReviewChecklistPanel.createOrShow(context, store);
    panel.show('doc-without-state');
    const before = posted().filter((m) => m.command === 'checklist').length;

    await send({ command: 'setStatus', id: 'no-such-id', status: 'fixed' });

    expect(posted().filter((m) => m.command === 'checklist')).toHaveLength(before);
  });

  it('パネルを破棄すると、以後のrefreshIfShowingは何もしない(例外を投げない)', () => {
    const context = createContextWithWorkspaceState();
    const store = new ReviewChecklistStore(context);
    const panel = ReviewChecklistPanel.createOrShow(context, store);
    panel.show('doc-1');

    lastCreatedWebviewPanel?.fireDispose();

    expect(() =>
      ReviewChecklistPanel.refreshIfShowing('doc-1', [])
    ).not.toThrow();
  });
});
