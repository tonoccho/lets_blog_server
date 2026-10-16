import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import {
  appliedEdits,
  lastCreatedWebviewPanel,
  resetMocks,
  setWarningResponse,
  shownInformations,
} from '../__mocks__/vscode';
import { DiagramGalleryPanel } from '../diagramGalleryPanel';

import { setConfiguration } from '../__mocks__/vscode';
import * as api from '../apiClient';
import { LocalHttpServer, Reply, jsonReply } from './support/localHttpServer';
import { createContext as createTestContext, loginAs } from './support/extensionContext';
import { payloadOf, send } from './support/panelKit';

// 外部通信(gateway の /api/diagrams)だけを実サーバーで受ける。config.ts・apiClient.ts は実物。
let server: LocalHttpServer;
let loggedInContext: vscode.ExtensionContext;

/** ダイアグラムギャラリーが叩く API の応答。必要なものだけ差し替える。 */
function serveDiagrams(overrides: Record<string, Reply> = {}): void {
  server.respondWith((request) => {
    const pathname = request.path.split('?')[0];
    const key = `${request.method} ${pathname}`;
    if (overrides[key]) return overrides[key];
    if (request.method === 'GET' && pathname.endsWith('/svg')) {
      return { status: 200, bodyBytes: Buffer.from('<svg></svg>') };
    }
    if (request.method === 'DELETE') return { status: 204 };
    return { status: 404, body: JSON.stringify({ error: `no route: ${key}` }) };
  });
}

function requestedPaths(): string[] {
  return server.recorded.map((r) => `${r.method} ${r.path}`);
}

/** issue #1063: Image Galleryと同じ不具合がDiagram Galleryにも存在する。 */

let extensionRoot: string;
let articleADir: string;
let articleBDir: string;

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

function assetFiles(dir: string): string[] {
  const assetsDir = path.join(dir, 'assets');
  return fs.existsSync(assetsDir) ? fs.readdirSync(assetsDir).sort() : [];
}

beforeAll(() => {
  extensionRoot = path.resolve(__dirname, '..', '..');
});

beforeEach(async () => {
  resetMocks();
  api.clearResponseCache();
  server = await LocalHttpServer.start();
  setConfiguration('letsBlog.serverUrl', server.url);
  loggedInContext = createTestContext(`file://${extensionRoot}`);
  await loginAs(loggedInContext);
  serveDiagrams();
  articleADir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-diagram-gallery-a-'));
  articleBDir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-diagram-gallery-b-'));
});

afterEach(async () => {
  lastCreatedWebviewPanel?.fireDispose();
  await server.stop();
  resetMocks();
  fs.rmSync(articleADir, { recursive: true, force: true });
  fs.rmSync(articleBDir, { recursive: true, force: true });
});

describe('DiagramGalleryPanel', () => {
  it('記事Aで開いたあと閉じずに記事Bで再度開くと、挿入は記事Bへ行われる (issue #1063)', async () => {
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    const editorB = createEditor('file:///workspace/b.md');
    DiagramGalleryPanel.createOrShow(loggedInContext, editorB, articleBDir, 2);

    await send({ command: 'insertDiagram', diagramId: 5, name: '構成図' });

    expect(assetFiles(articleADir)).toEqual([]);
    const filesB = assetFiles(articleBDir);
    expect(filesB).toHaveLength(1);
    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.uri).toBe('file:///workspace/b.md');
    expect(payloadOf('diagramInserted')).toEqual({ fileName: filesB[0] });
  });

  it('記事名を挿入通知メッセージへ含める (issue #1063 要件4)', async () => {
    const editorB = createEditor('file:///workspace/b.md');
    DiagramGalleryPanel.createOrShow(loggedInContext, editorB, articleBDir, 2);

    await send({ command: 'insertDiagram', diagramId: 5, name: '構成図' });

    expect(shownInformations.join('\n')).toContain(path.basename(articleBDir));
  });

  it('再度開かずに連続して挿入しても、同じ記事へ書き込まれ続ける(回帰確認)', async () => {
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'insertDiagram', diagramId: 1, name: '一つ目' });
    await send({ command: 'insertDiagram', diagramId: 2, name: '二つ目' });

    expect(assetFiles(articleADir)).toHaveLength(2);
    expect(assetFiles(articleBDir)).toEqual([]);
  });

  it('loadDiagramsで一覧を取得し、Webviewへ返す', async () => {
    const diagrams = [{ id: 1, name: '図1' }, { id: 2, name: '図2' }];
    serveDiagrams({ 'GET /api/diagrams': jsonReply(diagrams) });
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'loadDiagrams' });

    expect(requestedPaths()).toEqual(['GET /api/diagrams?projectId=1']);
    expect(payloadOf('diagramList')).toEqual({ diagrams });
  });

  it('loadThumbnailsで指定IDのサムネイルを取得する', async () => {
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'loadThumbnails', diagramIds: [1, 2] });

    expect(requestedPaths().sort()).toEqual(['GET /api/diagrams/1/svg', 'GET /api/diagrams/2/svg']);
    const thumbnails = payloadOf('thumbnails') as { thumbnails: { id: number; dataUri: string }[] };
    expect(thumbnails.thumbnails.map((t) => t.id)).toEqual([1, 2]);
  });

  it('deleteDiagramで利用者が確認すると削除される', async () => {
    setWarningResponse('削除する');
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    serveDiagrams({ 'GET /api/diagrams': jsonReply([{ id: 7, name: '図' }]) });
    await send({ command: 'loadDiagrams' });
    await send({ command: 'loadDiagrams' });
    expect(requestedPaths()).toEqual(['GET /api/diagrams?projectId=1']);

    await send({ command: 'deleteDiagram', diagramId: 7 });

    expect(requestedPaths()).toContain('DELETE /api/diagrams/7');
    expect(payloadOf('diagramDeleted')).toEqual({ diagramId: 7 });
    // 削除でそのプロジェクトの一覧キャッシュが破棄され、次の一覧取得はサーバーへ取りに行く。
    await send({ command: 'loadDiagrams' });
    expect(requestedPaths().filter((p) => p.startsWith('GET /api/diagrams?'))).toHaveLength(2);
  });

  it('deleteDiagramで利用者がキャンセルすると削除されない', async () => {
    setWarningResponse(undefined);
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'deleteDiagram', diagramId: 7 });

    expect(server.recorded).toHaveLength(0);
    expect(payloadOf('deleteCancelled')).toEqual({ diagramId: 7 });
  });

  it('cancelを受け取ると進行中の処理を中断する', async () => {
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await expect(send({ command: 'cancel' }, false)).resolves.toBeUndefined();
  });

  it('insertDiagramでnameが空(空白のみ)のとき、既定のalt文言を使う', async () => {
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'insertDiagram', diagramId: 5, name: '   ' });

    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.text).toContain('![ダイアグラム]');
  });
});
