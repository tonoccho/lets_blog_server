import * as path from 'path';
import * as vscode from 'vscode';
import { lastCreatedWebviewPanel, resetMocks } from '../__mocks__/vscode';
import { ReviewChecklistPanel } from '../reviewChecklistPanel';
import { ReviewChecklistStore } from '../reviewChecklistStore';
import { REVIEW_STEPS, StepFinding } from '../proofreadLogic';
const JUMP_NOT_FOUND_MESSAGE = '本文に見つかりません';

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

function lastChecklistPayload(): {
  groups: { stepKey: string; stepLabel: string; items: unknown[] }[];
  skippedSteps: { stepKey: string; stepLabel: string; reason: string }[];
} {
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

  it('スキップされたステップを、指摘0件の状態でもスキップ情報としてWebviewへ送る(issue #1545 AC1/AC2)', async () => {
    const context = createContextWithWorkspaceState();
    const store = new ReviewChecklistStore(context);
    await store.recordReview('doc-1', [], 'h', [{ step: REVIEW_STEPS[1], reason: '本文が短いため' }]);

    const panel = ReviewChecklistPanel.createOrShow(context, store);
    panel.show('doc-1');

    const payload = lastChecklistPayload() as unknown as { isEmpty: boolean };
    expect(lastChecklistPayload().skippedSteps).toEqual([
      { stepKey: REVIEW_STEPS[1].key, stepLabel: REVIEW_STEPS[1].label, reason: '本文が短いため' },
    ]);
    expect(payload.isEmpty).toBe(true);
  });

  it('スキップ情報を持たない既存の永続化状態でも、項目と対応状態を従来どおり表示する(issue #1545 AC4)', async () => {
    const context = createContextWithWorkspaceState();
    const store = new ReviewChecklistStore(context);
    const recorded = await store.recordReview('doc-1', [finding('AはBです')], 'h');
    await store.setStatus('doc-1', recorded.items[0].id, 'fixed');
    // #1216時点の保存形式(skippedStepsなし)へ戻す。
    const { bodyHash, items } = store.get('doc-1')!;
    await context.workspaceState.update('letsBlog.reviewChecklist.v1:doc-1', { bodyHash, items });

    const panel = ReviewChecklistPanel.createOrShow(context, store);
    panel.show('doc-1');

    const payload = lastChecklistPayload();
    expect((payload.groups[0].items[0] as { status: string }).status).toBe('fixed');
    expect(payload.skippedSteps).toEqual([]);
  });

  it('refreshIfShowingへ渡したスキップ情報で表示を更新する(issue #1545 AC3)', () => {
    const context = createContextWithWorkspaceState();
    const store = new ReviewChecklistStore(context);
    const panel = ReviewChecklistPanel.createOrShow(context, store);
    panel.show('doc-1');

    ReviewChecklistPanel.refreshIfShowing('doc-1', [], [{ stepKey: 'PROOFREADING', stepLabel: '校正チェック', reason: 'r' }]);
    expect(lastChecklistPayload().skippedSteps).toHaveLength(1);

    ReviewChecklistPanel.refreshIfShowing('doc-1', []);
    expect(lastChecklistPayload().skippedSteps).toHaveLength(0);
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

  describe('件数と空状態(issue #1225)', () => {
    function lastView(): { unresolvedCount: number; recorded: boolean; isEmpty: boolean } {
      return lastChecklistPayload() as never;
    }

    it('未対応の件数を送り、対応状態を変えると件数が追随する(AC3, AC4)', async () => {
      const context = createContextWithWorkspaceState();
      const store = new ReviewChecklistStore(context);
      const state = await store.recordReview('doc-1', [finding('A'), finding('B')], 'h');
      const panel = ReviewChecklistPanel.createOrShow(context, store);
      panel.show('doc-1');
      expect(lastView().unresolvedCount).toBe(2);

      await send({ command: 'setStatus', id: state.items[0].id, status: 'fixed' });
      expect(lastView().unresolvedCount).toBe(1);
    });

    it('記録済みで0件なら isEmpty(AC5)、未実行の記事なら recorded=false', async () => {
      const context = createContextWithWorkspaceState();
      const store = new ReviewChecklistStore(context);
      await store.recordReview('doc-empty', [], 'h');
      const panel = ReviewChecklistPanel.createOrShow(context, store);

      panel.show('doc-empty');
      expect(lastView()).toMatchObject({ recorded: true, isEmpty: true, unresolvedCount: 0 });

      panel.show('doc-never-reviewed');
      expect(lastView()).toMatchObject({ recorded: false, isEmpty: false });
    });
  });

  describe('指摘箇所へのジャンプ(issue #1225)', () => {
    interface FakeEditor {
      selection?: { start: { offset: number }; end: { offset: number } };
      revealed?: { start: { offset: number } };
    }

    function stubDocument(text: string): { editor: FakeEditor; opened: string[]; shown: number } {
      const editor: FakeEditor = {};
      const opened: string[] = [];
      const result = { editor, opened, shown: 0 };
      const document = {
        getText: () => text,
        positionAt: (offset: number) => new vscode.Position(offset, 0),
      };
      jest.spyOn(vscode.workspace, 'openTextDocument').mockImplementation(((uri: { toString(): string }) => {
        opened.push(uri.toString());
        return Promise.resolve(document);
      }) as never);
      jest.spyOn(vscode.window, 'showTextDocument').mockImplementation((() => {
        result.shown += 1;
        return Promise.resolve({
          get selection() { return editor.selection; },
          set selection(value) { editor.selection = value as never; },
          revealRange: (range: { start: { offset: number } }) => { editor.revealed = range; },
        });
      }) as never);
      return result;
    }

    async function setup(text: string, quote: string): Promise<{ id: string; stub: ReturnType<typeof stubDocument> }> {
      const context = createContextWithWorkspaceState();
      const store = new ReviewChecklistStore(context);
      const state = await store.recordReview('file:///a.md', [finding(quote)], 'h');
      const stub = stubDocument(text);
      ReviewChecklistPanel.createOrShow(context, store).show('file:///a.md');
      return { id: state.items[0].id, stub };
    }

    afterEach(() => {
      jest.restoreAllMocks();
    });

    it('jumpを受けると、表示中の記事を開き、最初の一致へカーソルを移動して可視にする(AC1)', async () => {
      const text = '---\ntitle: 赤い\n---\n前文。赤い花。赤い実。';
      const { id, stub } = await setup(text, '赤い');

      await send({ command: 'jump', id });

      expect(stub.opened).toEqual(['file:///a.md']);
      expect(stub.editor.selection?.start.offset).toBe(text.indexOf('赤い花'));
      expect(stub.editor.selection?.end.offset).toBe(text.indexOf('赤い花') + '赤い'.length);
      expect(stub.editor.revealed?.start.offset).toBe(text.indexOf('赤い花'));
    });

    it('見つからなければ「本文に見つかりません」を送り、エディタを開かずカーソルも動かさない(AC2)', async () => {
      const { id, stub } = await setup('別の本文です', '消えた引用文');

      await send({ command: 'jump', id });

      const notFound = posted().filter((m) => m.command === 'jumpNotFound');
      expect(notFound).toHaveLength(1);
      expect(notFound[0].payload).toEqual({ message: JUMP_NOT_FOUND_MESSAGE });
      expect(stub.shown).toBe(0);
      expect(stub.editor.selection).toBeUndefined();
    });

    it('未知のIDのjumpは何もしない', async () => {
      const { stub } = await setup('本文', '本文');

      await send({ command: 'jump', id: 'no-such-id' });

      expect(stub.opened).toEqual([]);
      expect(posted().filter((m) => m.command === 'jumpNotFound')).toEqual([]);
    });

    it('showを呼ぶ前のjumpは何もしない(documentKey未設定)', async () => {
      const context = createContextWithWorkspaceState();
      const store = new ReviewChecklistStore(context);
      const stub = stubDocument('本文');
      ReviewChecklistPanel.createOrShow(context, store);

      await send({ command: 'jump', id: 'x' });

      expect(stub.opened).toEqual([]);
    });
  });
});
