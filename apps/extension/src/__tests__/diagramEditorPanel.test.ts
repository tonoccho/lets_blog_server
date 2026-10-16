import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { appliedEdits, lastCreatedWebviewPanel, resetMocks, shownInformations } from '../__mocks__/vscode';
import { DIAGRAM_REFERENCE_PATTERN, DiagramEditorPanel } from '../diagramEditorPanel';

import { setConfiguration } from '../__mocks__/vscode';
import * as api from '../apiClient';
import { LocalHttpServer, jsonReply } from './support/localHttpServer';
import { createContext as createTestContext, loginAs } from './support/extensionContext';

// 外部通信(gateway の /api/diagrams)だけを実サーバーで受ける。config.ts・apiClient.ts は実物。
let server: LocalHttpServer;
let extensionRoot: string;
let baseDir: string;

let loggedInContext: vscode.ExtensionContext;

function createContext(): vscode.ExtensionContext {
  return createTestContext(`file://${extensionRoot}`);
}

function createEditor(uri: string): vscode.TextEditor {
  return {
    document: {
      uri,
      getText: () => '---\ntitle: 記事\n---\n\n本文\n',
      positionAt: (offset: number) => offset,
      save: () => Promise.resolve(true),
    },
    selection: { active: 0 },
  } as unknown as vscode.TextEditor;
}

function posted(): { command: string; payload: unknown }[] {
  return (lastCreatedWebviewPanel?.webview.posted ?? []) as { command: string; payload: unknown }[];
}

/**
 * Webview からのメッセージを送り、拡張の応答(Webview への投稿・パネルを閉じる)を待つ。
 * 保存は実サーバーへの HTTP を挟むので、1 回の setImmediate では終わらない。
 */
async function send(message: unknown): Promise<void> {
  const panel = lastCreatedWebviewPanel;
  const before = posted().length;
  panel?.webview.postMessageToExtension(message);
  for (let i = 0; i < 200 && posted().length === before && !panel?.disposed; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

/**
 * issue #1063 要件3: DiagramEditorPanelは editor/baseDir/projectId に加えて mode
 * (create/edit)を保持する。modeは描画内容そのものを変えるため、既存パネルを
 * 再利用する際にはinitを送り直す必要がある(他の3パネルと違う点)。
 */
describe('DiagramEditorPanel: シングルトン再利用時のmode更新 (issue #1063)', () => {
  beforeAll(() => {
    extensionRoot = path.resolve(__dirname, '..', '..');
  });

  beforeEach(async () => {
    baseDir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-diagram-editor-'));
    resetMocks();
    api.clearResponseCache();
    server = await LocalHttpServer.start();
    setConfiguration('letsBlog.serverUrl', server.url);
    server.respondWith(jsonReply({ id: 1, name: '図', xml: '<mxfile/>', svg: '<svg/>' }));
    // パネルは起動時に config.ts でトークンを読むので、ログイン済みの context を使う。
    loggedInContext = createContext();
    await loginAs(loggedInContext);
  });

  afterEach(async () => {
    lastCreatedWebviewPanel?.fireDispose();
    resetMocks();
    fs.rmSync(baseDir, { recursive: true, force: true });
    await server.stop();
  });

  it('New Diagramを開いたあと閉じずにEdit Diagramを実行すると、編集モードのinitが再送される', async () => {
    DiagramEditorPanel.createOrShow(loggedInContext, createEditor('file:///a.md'), baseDir, 1, {
      kind: 'create',
    });
    await send({ command: 'ready' });
    expect(posted().filter((m) => m.command === 'init')).toHaveLength(1);

    DiagramEditorPanel.createOrShow(loggedInContext, createEditor('file:///a.md'), baseDir, 1, {
      kind: 'edit',
      diagramId: 42,
      name: '既存図',
      xml: '<mxfile>xml</mxfile>',
      existingFileName: 'diagram-42-111.svg',
    });

    const initMessages = posted().filter((m) => m.command === 'init');
    expect(initMessages).toHaveLength(2);
    expect(initMessages[1].payload).toEqual(
      expect.objectContaining({ mode: 'edit', name: '既存図', xml: '<mxfile>xml</mxfile>' })
    );
  });

  it('Edit Diagramを開いたあと閉じずにNew Diagramを実行すると、新規モードのタイトルへ戻る', async () => {
    DiagramEditorPanel.createOrShow(loggedInContext, createEditor('file:///a.md'), baseDir, 1, {
      kind: 'edit',
      diagramId: 1,
      name: '既存図',
      xml: '<mxfile/>',
      existingFileName: 'diagram-1-1.svg',
    });

    DiagramEditorPanel.createOrShow(loggedInContext, createEditor('file:///a.md'), baseDir, 1, { kind: 'create' });
    await send({ command: 'ready' });

    const initMessages = posted().filter((m) => m.command === 'init');
    expect(initMessages[initMessages.length - 1].payload).toEqual(
      expect.objectContaining({ mode: 'create', name: '無題のダイアグラム', xml: '' })
    );
  });

  it('新規ダイアグラムの挿入は記事名を含む通知を出し、記事へ挿入される (issue #1063 要件4)', async () => {
    server.respondWith(jsonReply({ id: 9, name: '新規図', xml: '<mxfile/>', svg: '<svg/>' }));
    const editor = createEditor('file:///a.md');
    DiagramEditorPanel.createOrShow(loggedInContext, editor, baseDir, 1, { kind: 'create' });

    await send({ command: 'insertNew', name: '新規図', xml: '<mxfile/>', svg: '<svg/>' });

    expect(payloadOf('inserted')).toEqual({ fileName: expect.stringMatching(/^diagram-9-\d+\.svg$/) });
    expect(shownInformations.join('\n')).toContain(path.basename(baseDir));
    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.uri).toBe('file:///a.md');
  });

  it('名前が空白だけのダイアグラムは、既定の代替テキストで記事へ挿入する', async () => {
    server.respondWith(jsonReply({ id: 9, name: ' ', xml: '<mxfile/>', svg: '<svg/>' }));
    DiagramEditorPanel.createOrShow(loggedInContext, createEditor('file:///a.md'), baseDir, 1, { kind: 'create' });

    await send({ command: 'insertNew', name: '   ', xml: '<mxfile/>', svg: '<svg/>' });

    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.text).toMatch(/^!\[ダイアグラム\]\(assets\/diagram-9-\d+\.svg\)$/);
  });

  it('上書き保存は記事名を含む通知を出す (issue #1063 要件4)', async () => {
    server.respondWith(jsonReply({ id: 1, name: '既存図', xml: '<mxfile/>', svg: '<svg/>' }));
    DiagramEditorPanel.createOrShow(loggedInContext, createEditor('file:///a.md'), baseDir, 1, {
      kind: 'edit',
      diagramId: 1,
      name: '既存図',
      xml: '<mxfile/>',
      existingFileName: 'diagram-1-1.svg',
    });

    await send({ command: 'saveOverwrite', xml: '<mxfile/>', svg: '<svg/>' });

    expect(payloadOf('saved')).toEqual({});
    expect(shownInformations.join('\n')).toContain(path.basename(baseDir));
  });

  it('別名保存(saveAsNew)は記事名を含む通知を出す (issue #1063 要件4)', async () => {
    server.respondWith(jsonReply({ id: 9, name: '新規図', xml: '<mxfile/>', svg: '<svg/>' }));
    DiagramEditorPanel.createOrShow(loggedInContext, createEditor('file:///a.md'), baseDir, 1, {
      kind: 'create',
    });

    await send({ command: 'saveAsNew', name: '新規図', xml: '<mxfile/>', svg: '<svg/>' });

    expect(payloadOf('saved')).toEqual({});
    expect(shownInformations.join('\n')).toContain(path.basename(baseDir));
  });

  it('cancelを受け取るとパネルを閉じる', async () => {
    DiagramEditorPanel.createOrShow(loggedInContext, createEditor('file:///a.md'), baseDir, 1, {
      kind: 'create',
    });
    const panel = lastCreatedWebviewPanel;

    await send({ command: 'cancel' });

    expect(panel?.disposed).toBe(true);
  });

  it('createモードでsaveOverwriteを受け取るとエラーを通知する', async () => {
    DiagramEditorPanel.createOrShow(loggedInContext, createEditor('file:///a.md'), baseDir, 1, {
      kind: 'create',
    });

    await send({ command: 'saveOverwrite', xml: '<mxfile/>', svg: '<svg/>' });

    expect(server.recorded).toHaveLength(0);
    expect(payloadOf('error')).toEqual(
      expect.objectContaining({ error: expect.stringContaining('編集対象のダイアグラムがありません') })
    );
  });
});

function payloadOf(command: string): unknown {
  const found = posted().filter((m) => m.command === command);
  if (found.length === 0) throw new Error(`${command} が送られていません: ${JSON.stringify(posted())}`);
  return found[found.length - 1].payload;
}

describe('DIAGRAM_REFERENCE_PATTERN', () => {
  it('ダイアグラムのMarkdown画像参照にマッチし、ファイル名とIDを抽出する', () => {
    const line = '![システム構成図](assets/diagram-42-1699999999999.svg)';
    const match = DIAGRAM_REFERENCE_PATTERN.exec(line);

    expect(match).not.toBeNull();
    expect(match?.[1]).toBe('diagram-42-1699999999999.svg');
    expect(match?.[2]).toBe('42');
  });

  it('通常の画像参照にはマッチしない', () => {
    const line = '![猫の写真](assets/gallery-1-1699999999999.png)';
    expect(DIAGRAM_REFERENCE_PATTERN.test(line)).toBe(false);
  });

  it('assets/配下でないSVG参照にはマッチしない', () => {
    const line = '![外部](https://example.com/diagram-1-1699999999999.svg)';
    expect(DIAGRAM_REFERENCE_PATTERN.test(line)).toBe(false);
  });

  it('ID部分のみのタイムスタンプ欠落はマッチしない', () => {
    const line = '![不正](assets/diagram-42.svg)';
    expect(DIAGRAM_REFERENCE_PATTERN.test(line)).toBe(false);
  });
});
