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

jest.mock('../apiClient', () => ({
  listDiagrams: jest.fn(),
  getDiagramSvg: jest.fn(),
  deleteDiagram: jest.fn(),
}));
jest.mock('../config', () => ({
  requireAccessToken: jest.fn(async () => 'token'),
  getActor: jest.fn(async () => ({ id: 1, email: 'a@example.test', name: 'a' })),
}));

import * as api from '../apiClient';

const getDiagramSvgMock = api.getDiagramSvg as unknown as jest.Mock;
const listDiagramsMock = api.listDiagrams as unknown as jest.Mock;
const deleteDiagramMock = api.deleteDiagram as unknown as jest.Mock;

/** issue #1063: Image Galleryと同じ不具合がDiagram Galleryにも存在する。 */

let extensionRoot: string;
let articleADir: string;
let articleBDir: string;

function createContext(): vscode.ExtensionContext {
  return { extensionUri: `file://${extensionRoot}` } as unknown as vscode.ExtensionContext;
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
  articleADir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-diagram-gallery-a-'));
  articleBDir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-diagram-gallery-b-'));
  getDiagramSvgMock.mockReset();
  listDiagramsMock.mockReset();
  deleteDiagramMock.mockReset();
});

afterEach(() => {
  lastCreatedWebviewPanel?.fireDispose();
  resetMocks();
  fs.rmSync(articleADir, { recursive: true, force: true });
  fs.rmSync(articleBDir, { recursive: true, force: true });
});

describe('DiagramGalleryPanel', () => {
  it('記事Aで開いたあと閉じずに記事Bで再度開くと、挿入は記事Bへ行われる (issue #1063)', async () => {
    getDiagramSvgMock.mockResolvedValue('<svg></svg>');
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    const editorB = createEditor('file:///workspace/b.md');
    DiagramGalleryPanel.createOrShow(createContext(), editorB, articleBDir, 2);

    await send({ command: 'insertDiagram', diagramId: 5, name: '構成図' });

    expect(assetFiles(articleADir)).toEqual([]);
    const filesB = assetFiles(articleBDir);
    expect(filesB).toHaveLength(1);
    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.uri).toBe('file:///workspace/b.md');
    expect(payloadOf('diagramInserted')).toEqual({ fileName: filesB[0] });
  });

  it('記事名を挿入通知メッセージへ含める (issue #1063 要件4)', async () => {
    getDiagramSvgMock.mockResolvedValue('<svg></svg>');
    const editorB = createEditor('file:///workspace/b.md');
    DiagramGalleryPanel.createOrShow(createContext(), editorB, articleBDir, 2);

    await send({ command: 'insertDiagram', diagramId: 5, name: '構成図' });

    expect(shownInformations.join('\n')).toContain(path.basename(articleBDir));
  });

  it('再度開かずに連続して挿入しても、同じ記事へ書き込まれ続ける(回帰確認)', async () => {
    getDiagramSvgMock.mockResolvedValue('<svg></svg>');
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'insertDiagram', diagramId: 1, name: '一つ目' });
    await send({ command: 'insertDiagram', diagramId: 2, name: '二つ目' });

    expect(assetFiles(articleADir)).toHaveLength(2);
    expect(assetFiles(articleBDir)).toEqual([]);
  });

  it('loadDiagramsで一覧を取得し、Webviewへ返す', async () => {
    const diagrams = [{ id: 1 }, { id: 2 }];
    listDiagramsMock.mockResolvedValue(diagrams);
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'loadDiagrams' });

    expect(listDiagramsMock).toHaveBeenCalledWith('token', expect.anything(), 1);
    expect(payloadOf('diagramList')).toEqual({ diagrams });
  });

  it('loadThumbnailsで指定IDのサムネイルを取得する', async () => {
    getDiagramSvgMock.mockResolvedValue('<svg></svg>');
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'loadThumbnails', diagramIds: [1, 2] });

    expect(getDiagramSvgMock).toHaveBeenCalledTimes(2);
    expect(getDiagramSvgMock).toHaveBeenCalledWith('token', expect.anything(), 1);
    expect(getDiagramSvgMock).toHaveBeenCalledWith('token', expect.anything(), 2);
    const thumbnails = payloadOf('thumbnails') as { thumbnails: { id: number; dataUri: string }[] };
    expect(thumbnails.thumbnails.map((t) => t.id)).toEqual([1, 2]);
  });

  it('deleteDiagramで利用者が確認すると削除される', async () => {
    setWarningResponse('削除する');
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'deleteDiagram', diagramId: 7 });

    expect(deleteDiagramMock).toHaveBeenCalledWith('token', expect.anything(), 7, 1);
    expect(payloadOf('diagramDeleted')).toEqual({ diagramId: 7 });
  });

  it('deleteDiagramで利用者がキャンセルすると削除されない', async () => {
    setWarningResponse(undefined);
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'deleteDiagram', diagramId: 7 });

    expect(deleteDiagramMock).not.toHaveBeenCalled();
    expect(payloadOf('deleteCancelled')).toEqual({ diagramId: 7 });
  });

  it('cancelを受け取ると進行中の処理を中断する', async () => {
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await expect(send({ command: 'cancel' })).resolves.toBeUndefined();
  });

  it('insertDiagramでnameが空(空白のみ)のとき、既定のalt文言を使う', async () => {
    getDiagramSvgMock.mockResolvedValue('<svg></svg>');
    const editorA = createEditor('file:///workspace/a.md');
    DiagramGalleryPanel.createOrShow(createContext(), editorA, articleADir, 1);

    await send({ command: 'insertDiagram', diagramId: 5, name: '   ' });

    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.text).toContain('![ダイアグラム]');
  });
});
