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
import { SectionContext } from '../headingContext';
import { SectionGenPanel } from '../sectionGenPanel';
import { LocalHttpServer, jsonReply } from './support/localHttpServer';
import { createContext, loginAs, selectProject } from './support/extensionContext';
import { payloadOf, posted, send, waitFor } from './support/panelKit';

// 外部通信(gateway の POST /api/ai/section)だけを実サーバーで受ける。config.ts・apiClient.ts は実物。
let server: LocalHttpServer;
let context: ReturnType<typeof createContext>;

const SECTION: SectionContext = { mode: 'body', heading: '背景', headingLevel: 2, subsectionHeadings: [], precedingContext: '' };

function createEditor(selectionEmpty: boolean): vscode.TextEditor {
  return {
    document: {
      uri: 'file:///workspace/a.md',
      getText: () => (selectionEmpty ? '' : '選択中の文'),
      save: () => Promise.resolve(true),
    },
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

describe('SectionGenPanel', () => {
  it('initは記事タイトル・選択中の文・見出しの文脈・AIプロバイダー設定を返す', async () => {
    setConfiguration('letsBlog.aiProvider', 'OLLAMA');
    SectionGenPanel.createOrShow(context, createEditor(false), '入門記事', SECTION);

    await send({ command: 'init' });

    expect(payloadOf('init')).toEqual({
      articleTitle: '入門記事',
      selectedText: '選択中の文',
      sectionContext: SECTION,
      defaultAiProvider: 'OLLAMA',
    });
  });

  it('記事タイトルが無ければ、initは空文字を返す', async () => {
    SectionGenPanel.createOrShow(context, createEditor(true), undefined, SECTION);

    await send({ command: 'init' });

    expect(payloadOf('init')).toEqual(expect.objectContaining({ articleTitle: '', selectedText: '' }));
  });

  it('generateはパラメータに選択中のプロジェクトを足してサーバーへ送り、結果をWebviewへ返す', async () => {
    await selectProject(context, 7);
    server.respondWith(jsonReply({ result: '生成した本文', sources: [] }));
    SectionGenPanel.createOrShow(context, createEditor(true), '入門記事', SECTION);

    await send({ command: 'generate', params: { mode: 'body', heading: '背景' } });

    expect(server.recorded[0].path).toBe('/api/ai/section');
    expect(JSON.parse(server.recorded[0].body)).toEqual({ mode: 'body', heading: '背景', projectId: 7 });
    expect(payloadOf('generated')).toEqual({ result: '生成した本文', sources: [], searchNote: null });
  });

  it('insertはカーソル位置へ挿入し、保存して通知する', async () => {
    SectionGenPanel.createOrShow(context, createEditor(true), '入門記事', SECTION);

    await send({ command: 'insert', text: '生成した本文' });

    expect(appliedEdits).toEqual([{ kind: 'insert', uri: 'file:///workspace/a.md', text: '生成した本文' }]);
    expect(payloadOf('inserted')).toEqual({});
    expect(shownInformations).toEqual(['記事に挿入しました。']);
  });

  it('選択範囲があるinsertは、その範囲を置き換える', async () => {
    SectionGenPanel.createOrShow(context, createEditor(false), '入門記事', SECTION);

    await send({ command: 'insert', text: '置き換え後' });

    expect(appliedEdits).toEqual([{ kind: 'replace', uri: 'file:///workspace/a.md', text: '置き換え後' }]);
  });

  it('cancelは応答待ちのgenerateを中断し、キャンセルとして通知する', async () => {
    server.respondWith({ status: 200, hang: true });
    SectionGenPanel.createOrShow(context, createEditor(true), '入門記事', SECTION);
    void send({ command: 'generate', params: { mode: 'body', heading: '背景' } }, false);
    await waitFor(() => server.recorded.length === 1);

    await send({ command: 'cancel' });

    expect(posted().some((m) => m.command === 'cancelled')).toBe(true);
  });
});
