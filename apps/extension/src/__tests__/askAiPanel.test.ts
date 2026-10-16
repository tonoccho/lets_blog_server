import * as path from 'path';
import type * as vscode from 'vscode';
import {
  appliedEdits,
  lastCreatedWebviewPanel,
  resetMocks,
  setConfiguration,
  shownInformations,
} from '../__mocks__/vscode';
import * as api from '../apiClient';
import { AskAiPanel } from '../askAiPanel';
import { LocalHttpServer, jsonReply } from './support/localHttpServer';
import { createContext, loginAs } from './support/extensionContext';
import { payloadOf, posted, send, waitFor } from './support/panelKit';

// 外部通信(gateway の POST /api/ai/ask)だけを実サーバーで受ける。config.ts・apiClient.ts は実物。
let server: LocalHttpServer;
let context: vscode.ExtensionContext;

function createEditor(selectionEmpty: boolean): vscode.TextEditor {
  return {
    document: { uri: 'file:///workspace/a.md', save: () => Promise.resolve(true) },
    selection: { isEmpty: selectionEmpty, active: 0 },
  } as unknown as vscode.TextEditor;
}

beforeEach(async () => {
  resetMocks();
  api.clearResponseCache();
  server = await LocalHttpServer.start();
  setConfiguration('letsBlog.serverUrl', server.url);
  context = createContext(`file://${path.resolve(__dirname, '..', '..')}`);
  await loginAs(context);
});

afterEach(async () => {
  lastCreatedWebviewPanel?.fireDispose();
  resetMocks();
  await server.stop();
});

describe('AskAiPanel', () => {
  it('initで拡張側のAIプロバイダー設定をWebviewへ返す', async () => {
    setConfiguration('letsBlog.aiProvider', 'CLAUDE');
    AskAiPanel.createOrShow(context, createEditor(true));

    await send({ command: 'init' });

    expect(payloadOf('init')).toEqual({ defaultAiProvider: 'CLAUDE' });
  });

  it('askは質問とプロバイダー・選択中のプロジェクトをサーバーへ送り、回答をWebviewへ返す', async () => {
    server.respondWith(jsonReply({ result: '要約', sources: [{ title: '出典', url: 'https://example.test/a' }] }));
    AskAiPanel.createOrShow(context, createEditor(true));

    await send({ command: 'ask', question: '猫とは', provider: 'OPENAI' });

    expect(server.recorded[0].path).toBe('/api/ai/ask');
    expect(JSON.parse(server.recorded[0].body)).toEqual({ question: '猫とは', provider: 'OPENAI' });
    expect(payloadOf('answered')).toEqual({
      result: '要約',
      sources: [{ title: '出典', url: 'https://example.test/a' }],
      searchNote: null,
    });
  });

  it('回答の後のinsertは、要約に出典リンクを付けてカーソル位置へ挿入し、保存して通知する', async () => {
    server.respondWith(jsonReply({ result: '要約', sources: [{ title: '出典', url: 'https://example.test/a' }] }));
    AskAiPanel.createOrShow(context, createEditor(true));
    await send({ command: 'ask', question: '猫とは' });

    await send({ command: 'insert', text: '要約' });

    expect(appliedEdits).toEqual([
      {
        kind: 'insert',
        uri: 'file:///workspace/a.md',
        text: '要約\n\n---\n**出典:**\n- [出典](https://example.test/a)\n',
      },
    ]);
    expect(payloadOf('inserted')).toEqual({});
    expect(shownInformations).toEqual(['記事に挿入しました。']);
  });

  it('回答を得る前のinsertは出典を付けず、選択範囲があればそれを置き換える', async () => {
    AskAiPanel.createOrShow(context, createEditor(false));

    await send({ command: 'insert', text: '手で書いた文' });

    expect(appliedEdits).toEqual([{ kind: 'replace', uri: 'file:///workspace/a.md', text: '手で書いた文' }]);
  });

  it('ログインしていないaskはエラーを返し、サーバーへは送らない', async () => {
    context = createContext(`file://${path.resolve(__dirname, '..', '..')}`);
    AskAiPanel.createOrShow(context, createEditor(true));

    await send({ command: 'ask', question: '猫とは' });

    expect(server.recorded).toHaveLength(0);
    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('Login') });
  });

  it('cancelは応答待ちのaskを中断し、キャンセルとして通知する', async () => {
    server.respondWith({ status: 200, hang: true });
    AskAiPanel.createOrShow(context, createEditor(true));
    void send({ command: 'ask', question: '猫とは' }, false);
    await waitFor(() => server.recorded.length === 1);

    await send({ command: 'cancel' });

    expect(posted().some((m) => m.command === 'cancelled')).toBe(true);
  });
});
