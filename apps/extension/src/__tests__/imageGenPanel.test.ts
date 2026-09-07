import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import {
  appliedEdits,
  lastCreatedWebviewPanel,
  resetMocks,
  shownInformations,
} from '../__mocks__/vscode';
import { ImageGenPanel } from '../imageGenPanel';

jest.mock('../apiClient', () => ({
  generateImage: jest.fn(),
  generateImagePrompt: jest.fn(),
  getImageGenerationOptions: jest.fn(),
}));
jest.mock('../config', () => ({
  requireAccessToken: jest.fn(async () => 'token'),
  getActor: jest.fn(async () => ({ id: 1, email: 'a@example.test', name: 'a' })),
  getConfiguredAiProvider: jest.fn(() => 'OPENAI'),
}));

import * as api from '../apiClient';

const generateImageMock = api.generateImage as unknown as jest.Mock;
const generateImagePromptMock = api.generateImagePrompt as unknown as jest.Mock;
const getOptionsMock = api.getImageGenerationOptions as unknown as jest.Mock;

/**
 * Generate Imageパネルが「batch sizeで生成した全枚数を扱い、選択された1枚を保存する」ことの検証
 * (issue #1104)。パネルのUI操作はVSCode拡張ホストを要するため受け入れテストでは自動化できない
 * (apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.md)。Webview境界のメッセージと、
 * 実際にディスクへ書き出されるファイルをここで固定する。
 */

const IMAGE_A = 'AAAA';
const IMAGE_B = 'BBBB';

function result(id: number, fileName: string, dataBase64: string, mimeType = 'image/png') {
  return { id, fileName, dataBase64, mimeType };
}

let baseDir: string;
let extensionRoot: string;
let documentText: string;

function createContext(): vscode.ExtensionContext {
  return { extensionUri: `file://${extensionRoot}` } as unknown as vscode.ExtensionContext;
}

function createEditor(): vscode.TextEditor {
  return {
    document: {
      uri: 'file:///workspace/article.md',
      getText: () => documentText,
      positionAt: (offset: number) => offset,
      save: () => Promise.resolve(true),
    },
    selection: { active: 0 },
  } as unknown as vscode.TextEditor;
}

/** Webview側のスクリプトがpostMessageした状況を再現し、非同期処理の完了まで待つ。 */
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

function assetFiles(): string[] {
  const dir = path.join(baseDir, 'assets');
  return fs.existsSync(dir) ? fs.readdirSync(dir).sort() : [];
}

function open(prefill?: unknown): void {
  ImageGenPanel.createOrShow(
    createContext(),
    createEditor(),
    baseDir,
    7,
    prefill as never
  );
}

beforeAll(() => {
  extensionRoot = path.resolve(__dirname, '..', '..');
});

beforeEach(() => {
  baseDir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-imagegen-'));
  documentText = '---\ntitle: テスト記事\n---\n\n本文\n';
  generateImageMock.mockReset();
  generateImagePromptMock.mockReset();
  getOptionsMock.mockReset();
});

afterEach(() => {
  lastCreatedWebviewPanel?.fireDispose();
  resetMocks();
  fs.rmSync(baseDir, { recursive: true, force: true });
});

describe('ImageGenPanel', () => {
  it('生成完了ではファイル名の一覧だけをWebviewへ渡し、画像データは送らない', async () => {
    generateImageMock.mockResolvedValue([
      result(1, 'a.png', IMAGE_A),
      result(2, 'b.png', IMAGE_B),
    ]);
    open();

    await send({ command: 'generate', params: { prompt: '猫', batchSize: 2 } });

    expect(payloadOf('generated')).toEqual([{ fileName: 'a.png' }, { fileName: 'b.png' }]);
    expect(JSON.stringify(payloadOf('generated'))).not.toContain(IMAGE_A);
  });

  it('1枚だけ生成した場合も配列としてWebviewへ渡す', async () => {
    generateImageMock.mockResolvedValue([result(1, 'only.png', IMAGE_A)]);
    open();

    await send({ command: 'generate', params: { prompt: '犬', batchSize: 1 } });

    expect(payloadOf('generated')).toEqual([{ fileName: 'only.png' }]);
  });

  /**
   * 転送方式(issue #1105)。#1104までは全画像のbase64を1つのメッセージで送っていたため、
   * 256枚(batch size 16 × batch count 16)では数十MBがWebview境界を一度に越えていた。
   * パネルが実体を保持し、Webviewが必要になった1枚だけを取りに来る形へ改める。
   */
  it('requestImageで指定された1枚だけを返す', async () => {
    generateImageMock.mockResolvedValue([
      result(1, 'a.png', IMAGE_A),
      result(2, 'b.jpg', IMAGE_B, 'image/jpeg'),
    ]);
    open();
    await send({ command: 'generate', params: { prompt: '猫', batchSize: 2 } });

    await send({ command: 'requestImage', index: 1 });

    expect(payloadOf('imageData')).toEqual({
      index: 1,
      fileName: 'b.jpg',
      mimeType: 'image/jpeg',
      dataBase64: IMAGE_B,
    });
  });

  it('生成前のrequestImageはエラーになる', async () => {
    open();

    await send({ command: 'requestImage', index: 0 });

    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('先に画像を生成してください') });
  });

  it('生成枚数の範囲外のrequestImageはエラーになる', async () => {
    generateImageMock.mockResolvedValue([result(1, 'a.png', IMAGE_A)]);
    open();
    await send({ command: 'generate', params: { prompt: '猫', batchSize: 1 } });

    await send({ command: 'requestImage', index: 5 });

    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('選び直して') });
  });

  it('256枚を生成しても生成完了メッセージは数十KBに収まる', async () => {
    // 1920×1080のPNGをbase64にすると1枚1MBを超える。それを256枚ぶん保持させる。
    const oneMegabyte = 'A'.repeat(1_000_000);
    generateImageMock.mockResolvedValue(
      Array.from({ length: 256 }, (_, i) => result(i + 1, `image-${i}.png`, oneMegabyte))
    );
    open();

    await send({ command: 'generate', params: { prompt: '猫', batchSize: 16, batchCount: 16 } });

    const serialized = JSON.stringify(payloadOf('generated'));
    expect(serialized.length).toBeLessThan(20_000);
    expect(serialized).not.toContain(oneMegabyte);
  });

  it('2枚目を選んでアイキャッチにすると2枚目のファイルが保存され、front matterがそれを指す', async () => {
    generateImageMock.mockResolvedValue([
      result(1, 'a.png', IMAGE_A),
      result(2, 'b.jpg', IMAGE_B, 'image/jpeg'),
    ]);
    open();
    await send({ command: 'generate', params: { prompt: '猫', batchSize: 2 } });

    await send({ command: 'setAsEyecatch', index: 1 });

    const files = assetFiles();
    expect(files).toHaveLength(1);
    expect(files[0]).toMatch(/^eyecatch-\d+\.jpg$/);
    expect(fs.readFileSync(path.join(baseDir, 'assets', files[0])).toString('base64')).toBe(IMAGE_B);
    expect(payloadOf('eyecatchSet')).toEqual({ fileName: files[0] });
    const replaced = appliedEdits.find((e) => e.kind === 'replace');
    expect(replaced?.text).toContain(`featured_image: assets/${files[0]}`);
    expect(shownInformations.join('\n')).toContain(files[0]);
  });

  it('1枚目を選んでアセットにすると1枚目のファイルが保存され、Markdown参照が挿入される', async () => {
    generateImageMock.mockResolvedValue([
      result(1, 'a.png', IMAGE_A),
      result(2, 'b.png', IMAGE_B),
    ]);
    open();
    await send({ command: 'generate', params: { prompt: '夕焼けの海', batchSize: 2 } });

    await send({ command: 'addAsAsset', index: 0 });

    const files = assetFiles();
    expect(files).toHaveLength(1);
    expect(fs.readFileSync(path.join(baseDir, 'assets', files[0])).toString('base64')).toBe(IMAGE_A);
    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.text).toBe(`![夕焼けの海](assets/${files[0]})`);
    expect(payloadOf('assetAdded')).toEqual({ fileName: files[0] });
  });

  it('生成前にアイキャッチ設定が来たらエラーを返し、ファイルを作らない', async () => {
    open();

    await send({ command: 'setAsEyecatch', index: 0 });

    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('先に画像を生成してください') });
    expect(assetFiles()).toEqual([]);
  });

  it('生成前にアセット追加が来たらエラーを返し、ファイルを作らない', async () => {
    open();

    await send({ command: 'addAsAsset', index: 0 });

    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('先に画像を生成してください') });
    expect(assetFiles()).toEqual([]);
  });

  it('生成枚数の範囲外を選んで保存しようとするとエラーになり、ファイルを作らない', async () => {
    generateImageMock.mockResolvedValue([result(1, 'a.png', IMAGE_A)]);
    open();
    await send({ command: 'generate', params: { prompt: '猫', batchSize: 1 } });

    await send({ command: 'setAsEyecatch', index: 3 });

    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('選び直して') });
    expect(assetFiles()).toEqual([]);
  });

  it('既知でない拡張子のファイル名は.pngとして保存する', async () => {
    generateImageMock.mockResolvedValue([result(1, 'image.tiff', IMAGE_A, 'image/tiff')]);
    open();
    await send({ command: 'generate', params: { prompt: '猫', batchSize: 1 } });

    await send({ command: 'addAsAsset', index: 0 });

    expect(assetFiles()[0]).toMatch(/^generated-asset-\d+\.png$/);
  });

  it('プロンプト未生成のままアセット追加してもalt文言は空になる', async () => {
    generateImageMock.mockResolvedValue([result(1, 'a.png', IMAGE_A)]);
    open();
    await send({ command: 'generate', params: { batchSize: 1 } });

    await send({ command: 'addAsAsset', index: 0 });

    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.text).toBe(`![](assets/${assetFiles()[0]})`);
  });

  it('loadOptionsは拡張側のAIプロバイダー設定を合成して返す', async () => {
    getOptionsMock.mockResolvedValue({ checkpoints: ['x'], samplers: [], schedulers: [], loras: [] });
    open();

    await send({ command: 'loadOptions' });

    expect(payloadOf('options')).toEqual({
      checkpoints: ['x'],
      samplers: [],
      schedulers: [],
      loras: [],
      defaultAiProvider: 'OPENAI',
    });
    expect(posted().some((m) => m.command === 'prefill')).toBe(false);
  });

  it('Image Galleryから開いた場合はloadOptionsの後にprefillを送る', async () => {
    getOptionsMock.mockResolvedValue({ checkpoints: [], samplers: [], schedulers: [], loras: [] });
    open({ prompt: '再生成したい設定', batchSize: 2 });

    await send({ command: 'loadOptions' });

    expect(payloadOf('prefill')).toEqual({ prompt: '再生成したい設定', batchSize: 2 });
  });

  it('既に開いているパネルへprefillすると即座にWebviewへ送る', async () => {
    open();
    open({ prompt: '二度目の設定' });

    expect(payloadOf('prefill')).toEqual({ prompt: '二度目の設定' });
  });

  it('チャットからプロンプトを生成するとWebviewへ返す', async () => {
    generateImagePromptMock.mockResolvedValue({ prompt: 'a cat on the beach' });
    open();

    await send({ command: 'sendChat', history: [], message: '猫', provider: 'OLLAMA' });

    expect(payloadOf('promptGenerated')).toEqual({ prompt: 'a cat on the beach' });
  });

  it('cancelは進行中の生成を中断し、キャンセルとして通知する', async () => {
    let abortSignal: AbortSignal | undefined;
    generateImageMock.mockImplementation(
      (_key: string, _actor: unknown, _projectId: number, _params: unknown, signal: AbortSignal) =>
        new Promise((_resolve, reject) => {
          abortSignal = signal;
          signal.addEventListener('abort', () => reject(new (require('../errorHandler').CancelledError)('cancelled')));
        })
    );
    open();
    void send({ command: 'generate', params: { prompt: '猫', batchSize: 1 } });
    await new Promise((resolve) => setImmediate(resolve));

    await send({ command: 'cancel' });
    await new Promise((resolve) => setImmediate(resolve));

    expect(abortSignal?.aborted).toBe(true);
    expect(posted().some((m) => m.command === 'cancelled')).toBe(true);
  });
});
