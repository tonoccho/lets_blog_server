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
import { ImageGalleryPanel } from '../imageGalleryPanel';

import { setConfiguration } from '../__mocks__/vscode';
import * as api from '../apiClient';
import { LocalHttpServer, Reply, jsonReply } from './support/localHttpServer';
import { createContext as createTestContext, loginAs } from './support/extensionContext';
import { payloadOf, posted, send, waitFor } from './support/panelKit';

// 外部通信(gateway の /api/generated-images)だけを実サーバーで受ける。config.ts・apiClient.ts・
// imageGenPanel.ts は実物。
let server: LocalHttpServer;
let loggedInContext: vscode.ExtensionContext;

/** 画像ギャラリーが叩く API の応答。必要なものだけ差し替える。 */
function serveGallery(overrides: Record<string, Reply> = {}): void {
  server.respondWith((request) => {
    const pathname = request.path.split('?')[0];
    const key = `${request.method} ${pathname}`;
    if (overrides[key]) return overrides[key];
    if (request.method === 'GET' && pathname.endsWith('/file')) {
      return { status: 200, bodyBytes: Buffer.from('AAAA') };
    }
    if (request.method === 'DELETE') return { status: 204 };
    return { status: 404, body: JSON.stringify({ error: `no route: ${key}` }) };
  });
}

function requestedPaths(): string[] {
  return server.recorded.map((r) => `${r.method} ${r.path}`);
}

/**
 * issue #1063: シングルトンパネルが生成時のTextEditorを掴み続け、記事を切り替えた後の
 * ギャラリー挿入が前の記事へ書き込まれる不具合の検証。パネルのUI操作はVSCode拡張ホストを
 * 要するため受け入れテストでは自動化できない(apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.md)。
 */

let extensionRoot: string;
let articleADir: string;
let articleBDir: string;
let articleAText: string;
let articleBText: string;

function createEditor(uri: string, getText: () => string): vscode.TextEditor {
  return {
    document: {
      uri,
      getText,
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
  serveGallery();
  articleADir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-gallery-a-'));
  articleBDir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-gallery-b-'));
  articleAText = '---\ntitle: 記事A\n---\n\n本文A\n';
  articleBText = '---\ntitle: 記事B\n---\n\n本文B\n';
});

afterEach(async () => {
  lastCreatedWebviewPanel?.fireDispose();
  await server.stop();
  resetMocks();
  fs.rmSync(articleADir, { recursive: true, force: true });
  fs.rmSync(articleBDir, { recursive: true, force: true });
});

describe('ImageGalleryPanel', () => {
  it('記事Aで開いたあと閉じずに記事Bで再度開くと、挿入は記事Bへ行われる (issue #1063)', async () => {
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    const editorB = createEditor('file:///workspace/b.md', () => articleBText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorB, articleBDir, 2);

    await send({ command: 'insertImage', imageId: 10, prompt: '猫' });

    expect(assetFiles(articleADir)).toEqual([]);
    const filesB = assetFiles(articleBDir);
    expect(filesB).toHaveLength(1);
    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.uri).toBe('file:///workspace/b.md');
    expect(inserted?.text).toBe(`![猫](assets/${filesB[0]})`);
    expect(payloadOf('imageInserted')).toEqual({ fileName: filesB[0] });
  });

  it('記事名を挿入通知メッセージへ含める (issue #1063 要件4)', async () => {
    const editorB = createEditor('file:///workspace/b.md', () => articleBText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorB, articleBDir, 2);

    await send({ command: 'insertImage', imageId: 10, prompt: '猫' });

    const articleName = path.basename(articleBDir);
    expect(shownInformations.join('\n')).toContain(articleName);
  });

  it('アイキャッチ設定でも記事名を通知メッセージへ含める (issue #1063 要件4)', async () => {
    const editorB = createEditor('file:///workspace/b.md', () => articleBText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorB, articleBDir, 2);

    await send({ command: 'setAsEyecatch', imageId: 10 });

    expect(shownInformations.join('\n')).toContain(path.basename(articleBDir));
    const replaced = appliedEdits.find((e) => e.kind === 'replace');
    expect(replaced?.text).toContain('featured_image:');
  });

  it('再度開かずに連続して挿入しても、同じ記事へ書き込まれ続ける(回帰確認)', async () => {
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'insertImage', imageId: 1, prompt: '一枚目' });
    await send({ command: 'insertImage', imageId: 2, prompt: '二枚目' });

    expect(assetFiles(articleADir)).toHaveLength(2);
    expect(assetFiles(articleBDir)).toEqual([]);
    const inserts = appliedEdits.filter((e) => e.kind === 'insert');
    expect(inserts.every((e) => e.uri === 'file:///workspace/a.md')).toBe(true);
  });

  it('loadImagesで一覧を取得し、Webviewへ返す', async () => {
    const images = [{ id: 1 }, { id: 2 }];
    serveGallery({ 'GET /api/generated-images': jsonReply(images) });
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'loadImages' });

    expect(requestedPaths()).toEqual(['GET /api/generated-images?projectId=1']);
    expect(payloadOf('imageList')).toEqual(
      expect.objectContaining({ images, thumbnailsPerPage: 12 })
    );
  });

  it('loadThumbnailsで指定IDのサムネイルを取得する', async () => {
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'loadThumbnails', imageIds: [1, 2] });

    expect(requestedPaths().sort()).toEqual([
      'GET /api/generated-images/1/file',
      'GET /api/generated-images/2/file',
    ]);
    const thumbnails = payloadOf('thumbnails') as { thumbnails: { id: number; dataUri: string }[] };
    expect(thumbnails.thumbnails.map((t) => t.id)).toEqual([1, 2]);
  });

  it('deleteImageで利用者が確認すると削除され、キャッシュが無効化される', async () => {
    setWarningResponse('削除する');
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    serveGallery({ 'GET /api/generated-images': jsonReply([{ id: 7 }]) });
    await send({ command: 'loadImages' });
    await send({ command: 'loadImages' });
    expect(requestedPaths()).toEqual(['GET /api/generated-images?projectId=1']);

    await send({ command: 'deleteImage', imageId: 7 });

    expect(payloadOf('imageDeleted')).toEqual({ imageId: 7 });
    expect(requestedPaths()).toContain('DELETE /api/generated-images/7');
    // 削除でそのプロジェクトの一覧キャッシュが破棄され、次の一覧取得はサーバーへ取りに行く。
    await send({ command: 'loadImages' });
    expect(requestedPaths().filter((p) => p.startsWith('GET /api/generated-images?'))).toHaveLength(2);
  });

  it('deleteImageで利用者がキャンセルすると削除されない', async () => {
    setWarningResponse(undefined);
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'deleteImage', imageId: 7 });

    expect(server.recorded).toHaveLength(0);
    expect(payloadOf('deleteCancelled')).toEqual({ imageId: 7 });
  });

  it('regenerateWithSettingsで生成パラメータを取得し、Generate Imageパネルを開く (issue #294)', async () => {
    const detail = { id: 3, prompt: '猫' };
    serveGallery({
      'GET /api/generated-images/3': jsonReply(detail),
      'GET /api/ai/image-options': jsonReply({ checkpoints: [], samplers: [], schedulers: [], loras: [] }),
    });
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    const galleryPanel = lastCreatedWebviewPanel;
    await send({ command: 'regenerateWithSettings', imageId: 3 }, false);
    await waitFor(() => lastCreatedWebviewPanel !== galleryPanel);

    expect(requestedPaths()).toEqual(['GET /api/generated-images/3']);
    // 実物の Generate Image パネルが開き、取得した設定を初回の loadOptions に続けてフォームへ反映する。
    expect(lastCreatedWebviewPanel?.webview.html).toContain('imageGen');
    await send({ command: 'loadOptions' });
    await waitFor(() => posted().some((m) => m.command === 'prefill'));
    expect(payloadOf('prefill')).toEqual(detail);
    // 後続のテストが閉じ忘れたギャラリーのパネルを掴まないよう、ここで閉じる。
    galleryPanel?.fireDispose();
  });

  it('cancelを受け取ると進行中の処理を中断する', async () => {
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await expect(send({ command: 'cancel' }, false)).resolves.toBeUndefined();
  });

  it('insertImageでpromptが空のとき、既定のalt文言を使う', async () => {
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'insertImage', imageId: 10, prompt: '' });

    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.text).toContain('![生成画像]');
  });

  it('insertImageでpromptが未指定のとき、既定のalt文言を使う', async () => {
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(loggedInContext, editorA, articleADir, 1);

    await send({ command: 'insertImage', imageId: 10 });

    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.text).toContain('![生成画像]');
  });
});
