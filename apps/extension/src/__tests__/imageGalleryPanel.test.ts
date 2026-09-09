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

jest.mock('../apiClient', () => ({
  listGeneratedImages: jest.fn(),
  downloadGeneratedImage: jest.fn(),
  deleteGeneratedImage: jest.fn(),
  invalidateProjectCache: jest.fn(),
  getGeneratedImageDetail: jest.fn(),
}));
jest.mock('../config', () => ({
  requireAccessToken: jest.fn(async () => 'token'),
  getActor: jest.fn(async () => ({ id: 1, email: 'a@example.test', name: 'a' })),
}));
jest.mock('../imageGenPanel', () => ({
  ImageGenPanel: { createOrShow: jest.fn() },
}));

import * as api from '../apiClient';
import { ImageGenPanel } from '../imageGenPanel';

const downloadMock = api.downloadGeneratedImage as unknown as jest.Mock;
const listGeneratedImagesMock = api.listGeneratedImages as unknown as jest.Mock;
const deleteGeneratedImageMock = api.deleteGeneratedImage as unknown as jest.Mock;
const invalidateProjectCacheMock = api.invalidateProjectCache as unknown as jest.Mock;
const getGeneratedImageDetailMock = api.getGeneratedImageDetail as unknown as jest.Mock;
const imageGenPanelCreateOrShowMock = ImageGenPanel.createOrShow as unknown as jest.Mock;

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

function createContext(): vscode.ExtensionContext {
  return { extensionUri: `file://${extensionRoot}` } as unknown as vscode.ExtensionContext;
}

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

async function send(message: unknown): Promise<void> {
  lastCreatedWebviewPanel?.webview.postMessageToExtension(message);
  await new Promise((resolve) => setImmediate(resolve));
}

function posted(): { command: string; payload: unknown }[] {
  return (lastCreatedWebviewPanel?.webview.posted ?? []) as { command: string; payload: unknown }[];
}

function payloadOf(command: string): unknown {
  const found = posted().filter((m) => m.command === command);
  if (found.length === 0) throw new Error(`${command} が送られていません: ${JSON.stringify(posted())}`);
  return found[found.length - 1].payload;
}

function assetFiles(dir: string): string[] {
  const assetsDir = path.join(dir, 'assets');
  return fs.existsSync(assetsDir) ? fs.readdirSync(assetsDir).sort() : [];
}

beforeAll(() => {
  extensionRoot = path.resolve(__dirname, '..', '..');
});

beforeEach(() => {
  articleADir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-gallery-a-'));
  articleBDir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-gallery-b-'));
  articleAText = '---\ntitle: 記事A\n---\n\n本文A\n';
  articleBText = '---\ntitle: 記事B\n---\n\n本文B\n';
  downloadMock.mockReset();
  listGeneratedImagesMock.mockReset();
  deleteGeneratedImageMock.mockReset();
  invalidateProjectCacheMock.mockReset();
  getGeneratedImageDetailMock.mockReset();
  imageGenPanelCreateOrShowMock.mockReset();
});

afterEach(() => {
  lastCreatedWebviewPanel?.fireDispose();
  resetMocks();
  fs.rmSync(articleADir, { recursive: true, force: true });
  fs.rmSync(articleBDir, { recursive: true, force: true });
});

describe('ImageGalleryPanel', () => {
  it('記事Aで開いたあと閉じずに記事Bで再度開くと、挿入は記事Bへ行われる (issue #1063)', async () => {
    downloadMock.mockResolvedValue(Buffer.from('AAAA'));
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    const editorB = createEditor('file:///workspace/b.md', () => articleBText);
    ImageGalleryPanel.createOrShow(createContext(), editorB, articleBDir, 2);

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
    downloadMock.mockResolvedValue(Buffer.from('AAAA'));
    const editorB = createEditor('file:///workspace/b.md', () => articleBText);
    ImageGalleryPanel.createOrShow(createContext(), editorB, articleBDir, 2);

    await send({ command: 'insertImage', imageId: 10, prompt: '猫' });

    const articleName = path.basename(articleBDir);
    expect(shownInformations.join('\n')).toContain(articleName);
  });

  it('アイキャッチ設定でも記事名を通知メッセージへ含める (issue #1063 要件4)', async () => {
    downloadMock.mockResolvedValue(Buffer.from('AAAA'));
    const editorB = createEditor('file:///workspace/b.md', () => articleBText);
    ImageGalleryPanel.createOrShow(createContext(), editorB, articleBDir, 2);

    await send({ command: 'setAsEyecatch', imageId: 10 });

    expect(shownInformations.join('\n')).toContain(path.basename(articleBDir));
    const replaced = appliedEdits.find((e) => e.kind === 'replace');
    expect(replaced?.text).toContain('featured_image:');
  });

  it('再度開かずに連続して挿入しても、同じ記事へ書き込まれ続ける(回帰確認)', async () => {
    downloadMock.mockResolvedValue(Buffer.from('AAAA'));
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'insertImage', imageId: 1, prompt: '一枚目' });
    await send({ command: 'insertImage', imageId: 2, prompt: '二枚目' });

    expect(assetFiles(articleADir)).toHaveLength(2);
    expect(assetFiles(articleBDir)).toEqual([]);
    const inserts = appliedEdits.filter((e) => e.kind === 'insert');
    expect(inserts.every((e) => e.uri === 'file:///workspace/a.md')).toBe(true);
  });

  it('loadImagesで一覧を取得し、Webviewへ返す', async () => {
    const images = [{ id: 1 }, { id: 2 }];
    listGeneratedImagesMock.mockResolvedValue(images);
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'loadImages' });

    expect(listGeneratedImagesMock).toHaveBeenCalledWith('token', expect.anything(), 1);
    expect(payloadOf('imageList')).toEqual(
      expect.objectContaining({ images, thumbnailsPerPage: 12 })
    );
  });

  it('loadThumbnailsで指定IDのサムネイルを取得する', async () => {
    downloadMock.mockResolvedValue(Buffer.from('AAAA'));
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'loadThumbnails', imageIds: [1, 2] });

    expect(downloadMock).toHaveBeenCalledTimes(2);
    expect(downloadMock).toHaveBeenCalledWith('token', expect.anything(), 1);
    expect(downloadMock).toHaveBeenCalledWith('token', expect.anything(), 2);
    const thumbnails = payloadOf('thumbnails') as { thumbnails: { id: number; dataUri: string }[] };
    expect(thumbnails.thumbnails.map((t) => t.id)).toEqual([1, 2]);
  });

  it('deleteImageで利用者が確認すると削除され、キャッシュが無効化される', async () => {
    setWarningResponse('削除する');
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'deleteImage', imageId: 7 });

    expect(deleteGeneratedImageMock).toHaveBeenCalledWith('token', expect.anything(), 7);
    expect(invalidateProjectCacheMock).toHaveBeenCalledWith(1);
    expect(payloadOf('imageDeleted')).toEqual({ imageId: 7 });
  });

  it('deleteImageで利用者がキャンセルすると削除されない', async () => {
    setWarningResponse(undefined);
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'deleteImage', imageId: 7 });

    expect(deleteGeneratedImageMock).not.toHaveBeenCalled();
    expect(payloadOf('deleteCancelled')).toEqual({ imageId: 7 });
  });

  it('regenerateWithSettingsで生成パラメータを取得し、Generate Imageパネルを開く (issue #294)', async () => {
    const detail = { id: 3, prompt: '猫' };
    getGeneratedImageDetailMock.mockResolvedValue(detail);
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'regenerateWithSettings', imageId: 3 });

    expect(getGeneratedImageDetailMock).toHaveBeenCalledWith('token', expect.anything(), 3);
    expect(imageGenPanelCreateOrShowMock).toHaveBeenCalledWith(
      expect.anything(),
      editorA,
      articleADir,
      1,
      detail
    );
  });

  it('cancelを受け取ると進行中の処理を中断する', async () => {
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await expect(send({ command: 'cancel' })).resolves.toBeUndefined();
  });

  it('insertImageでpromptが空のとき、既定のalt文言を使う', async () => {
    downloadMock.mockResolvedValue(Buffer.from('AAAA'));
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'insertImage', imageId: 10, prompt: '' });

    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.text).toContain('![生成画像]');
  });

  it('insertImageでpromptが未指定のとき、既定のalt文言を使う', async () => {
    downloadMock.mockResolvedValue(Buffer.from('AAAA'));
    const editorA = createEditor('file:///workspace/a.md', () => articleAText);
    ImageGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'insertImage', imageId: 10 });

    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.text).toContain('![生成画像]');
  });
});
