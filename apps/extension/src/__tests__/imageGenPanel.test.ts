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

import { setConfiguration } from '../__mocks__/vscode';
import * as api from '../apiClient';
import { LocalHttpServer, jsonReply, routes } from './support/localHttpServer';
import { createContext as createTestContext, loginAs } from './support/extensionContext';
import { payloadOf, posted, send, waitFor } from './support/panelKit';

// 外部通信(gateway の /api/ai/*)だけを実サーバーで受ける。config.ts・apiClient.ts は実物。
let server: LocalHttpServer;
let loggedInContext: vscode.ExtensionContext;

/** POST /api/ai/image の応答(生成結果)を設定する。 */
function serveImages(images: ReturnType<typeof result>[]): void {
  server.respondWith(routes({ 'POST /api/ai/image': jsonReply({ images }) }));
}

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

function assetFiles(): string[] {
  const dir = path.join(baseDir, 'assets');
  return fs.existsSync(dir) ? fs.readdirSync(dir).sort() : [];
}

function open(prefill?: unknown): void {
  ImageGenPanel.createOrShow(
    loggedInContext,
    createEditor(),
    baseDir,
    7,
    prefill as never
  );
}

beforeAll(() => {
  extensionRoot = path.resolve(__dirname, '..', '..');
});

beforeEach(async () => {
  baseDir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-imagegen-'));
  documentText = '---\ntitle: テスト記事\n---\n\n本文\n';
  resetMocks();
  api.clearResponseCache();
  server = await LocalHttpServer.start();
  setConfiguration('letsBlog.serverUrl', server.url);
  setConfiguration('letsBlog.aiProvider', 'OPENAI');
  loggedInContext = createTestContext(`file://${extensionRoot}`);
  await loginAs(loggedInContext);
});

afterEach(async () => {
  lastCreatedWebviewPanel?.fireDispose();
  resetMocks();
  fs.rmSync(baseDir, { recursive: true, force: true });
  await server.stop();
});

describe('ImageGenPanel', () => {
  it('生成完了ではファイル名の一覧だけをWebviewへ渡し、画像データは送らない', async () => {
    serveImages([
      result(1, 'a.png', IMAGE_A),
      result(2, 'b.png', IMAGE_B),
    ]);
    open();

    await send({ command: 'generate', params: { prompt: '猫', batchSize: 2 } });

    expect(payloadOf('generated')).toEqual([{ fileName: 'a.png' }, { fileName: 'b.png' }]);
    expect(JSON.stringify(payloadOf('generated'))).not.toContain(IMAGE_A);
  });

  it('1枚だけ生成した場合も配列としてWebviewへ渡す', async () => {
    serveImages([result(1, 'only.png', IMAGE_A)]);
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
    serveImages([
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
    serveImages([result(1, 'a.png', IMAGE_A)]);
    open();
    await send({ command: 'generate', params: { prompt: '猫', batchSize: 1 } });

    await send({ command: 'requestImage', index: 5 });

    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('選び直して') });
  });

  it('256枚を生成しても生成完了メッセージは数十KBに収まる', async () => {
    // 実サーバーから 256 枚ぶんを実際に受け取るため、1 枚あたりは 100KB にする(メッセージの大きさが
    // 枚数や画像の大きさに比例しないことの確認なので、1MB でなくても不変条件は同じ)。
    const oneMegabyte = 'A'.repeat(100_000);
    serveImages(Array.from({ length: 256 }, (_, i) => result(i + 1, `image-${i}.png`, oneMegabyte)));
    open();

    await send({ command: 'generate', params: { prompt: '猫', batchSize: 16, batchCount: 16 } });

    const serialized = JSON.stringify(payloadOf('generated'));
    expect(serialized.length).toBeLessThan(20_000);
    expect(serialized).not.toContain(oneMegabyte);
  });

  it('2枚目を選んでアイキャッチにすると2枚目のファイルが保存され、front matterがそれを指す', async () => {
    serveImages([
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
    serveImages([
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
    serveImages([result(1, 'a.png', IMAGE_A)]);
    open();
    await send({ command: 'generate', params: { prompt: '猫', batchSize: 1 } });

    await send({ command: 'setAsEyecatch', index: 3 });

    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('選び直して') });
    expect(assetFiles()).toEqual([]);
  });

  it('既知でない拡張子のファイル名は.pngとして保存する', async () => {
    serveImages([result(1, 'image.tiff', IMAGE_A, 'image/tiff')]);
    open();
    await send({ command: 'generate', params: { prompt: '猫', batchSize: 1 } });

    await send({ command: 'addAsAsset', index: 0 });

    expect(assetFiles()[0]).toMatch(/^generated-asset-\d+\.png$/);
  });

  it('プロンプト未生成のままアセット追加してもalt文言は空になる', async () => {
    serveImages([result(1, 'a.png', IMAGE_A)]);
    open();
    await send({ command: 'generate', params: { batchSize: 1 } });

    await send({ command: 'addAsAsset', index: 0 });

    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.text).toBe(`![](assets/${assetFiles()[0]})`);
  });

  it('loadOptionsは拡張側のAIプロバイダー設定を合成して返す', async () => {
    server.respondWith(
      routes({ 'GET /api/ai/image-options': jsonReply({ checkpoints: ['x'], samplers: [], schedulers: [], loras: [] }) })
    );
    open();

    await send({ command: 'loadOptions' });

    expect(server.recorded[0].path).toBe('/api/ai/image-options?projectId=7');
    expect(payloadOf('options')).toEqual(
      expect.objectContaining({
        checkpoints: ['x'],
        samplers: [],
        schedulers: [],
        loras: [],
        defaultAiProvider: 'OPENAI',
      })
    );
    expect(posted().some((m) => m.command === 'prefill')).toBe(false);
  });

  it('Image Galleryから開いた場合はloadOptionsの後にprefillを送る', async () => {
    server.respondWith(
      routes({ 'GET /api/ai/image-options': jsonReply({ checkpoints: [], samplers: [], schedulers: [], loras: [] }) })
    );
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
    server.respondWith(
      routes({ 'POST /api/projects/7/ai/generate-image-prompt': jsonReply({ prompt: 'a cat on the beach' }) })
    );
    open();

    await send({ command: 'sendChat', history: [], message: '猫', provider: 'OLLAMA' });

    expect(JSON.parse(server.recorded[0].body)).toEqual({ history: [], message: '猫', provider: 'OLLAMA' });
    expect(payloadOf('promptGenerated')).toEqual({ prompt: 'a cat on the beach' });
  });

  /**
   * issue #1063: シングルトンパネルが生成時のTextEditor/baseDirを掴み続け、記事を
   * 切り替えた後の挿入・保存先が前の記事のままになる不具合の検証。
   */
  it('記事Aで開いたあと閉じずに記事Bで再度開くと、アセット追加は記事Bへ行われる (issue #1063)', async () => {
    serveImages([result(1, 'a.png', IMAGE_A)]);
    const baseDirA = baseDir;
    const documentTextA = documentText;
    const baseDirB = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-imagegen-b-'));
    const documentTextB = '---\ntitle: 記事B\n---\n\n本文B\n';

    ImageGenPanel.createOrShow(
      loggedInContext,
      { document: { uri: 'file:///workspace/a.md', getText: () => documentTextA, positionAt: (o: number) => o, save: () => Promise.resolve(true) }, selection: { active: 0 } } as unknown as vscode.TextEditor,
      baseDirA,
      7
    );
    ImageGenPanel.createOrShow(
      loggedInContext,
      { document: { uri: 'file:///workspace/b.md', getText: () => documentTextB, positionAt: (o: number) => o, save: () => Promise.resolve(true) }, selection: { active: 0 } } as unknown as vscode.TextEditor,
      baseDirB,
      8
    );

    await send({ command: 'generate', params: { prompt: '猫', batchSize: 1 } });
    await send({ command: 'addAsAsset', index: 0 });

    const filesA = fs.existsSync(path.join(baseDirA, 'assets')) ? fs.readdirSync(path.join(baseDirA, 'assets')) : [];
    expect(filesA).toEqual([]);
    const filesB = fs.readdirSync(path.join(baseDirB, 'assets'));
    expect(filesB).toHaveLength(1);
    const inserted = appliedEdits.find((e) => e.kind === 'insert');
    expect(inserted?.uri).toBe('file:///workspace/b.md');

    fs.rmSync(baseDirB, { recursive: true, force: true });
  });

  it('再度開かずに連続して保存しても、同じ記事へ書き込まれ続ける(回帰確認)', async () => {
    serveImages([result(1, 'a.png', IMAGE_A), result(2, 'b.png', IMAGE_B)]);
    open();
    await send({ command: 'generate', params: { prompt: '猫', batchSize: 2 } });

    // 2回目の保存はsetAsEyecatchで行う(addAsAssetを2連続にすると、ファイル名が
    // 同一ミリ秒のDate.now()に基づくため既存の別バグで上書きされうる。issue #1063の
    // 対象外のため、ここでは経路を分けて衝突を避ける)。
    await send({ command: 'addAsAsset', index: 0 });
    await send({ command: 'setAsEyecatch', index: 1 });

    expect(assetFiles()).toHaveLength(2);
    const inserts = appliedEdits.filter((e) => e.kind === 'insert' || e.kind === 'replace');
    expect(inserts.every((e) => e.uri === 'file:///workspace/article.md')).toBe(true);
  });

  it('cancelは進行中の生成を中断し、キャンセルとして通知する', async () => {
    // 要求を受けても応答を返さないサーバー(生成中)。
    server.respondWith({ status: 200, hang: true });
    open();
    void send({ command: 'generate', params: { prompt: '猫', batchSize: 1 } }, false);
    await waitFor(() => server.recorded.length === 1);

    await send({ command: 'cancel' });

    expect(server.recorded).toHaveLength(1);
    expect(posted().some((m) => m.command === 'cancelled')).toBe(true);
  });
});
