import { FakeElement, loadWebview, WebviewHarness } from './support/webviewDom';

/**
 * Generate Imageパネル(webviews/imageGen.html + imageGen.js)の検証。
 *
 * 拡張のWebviewはPlaywrightから到達できないため、受け入れ基準のうちフォーム・プレビュー・
 * 選択・画像データの受け渡しに関わる部分は、実際のHTML/JSをこのDOMスタブ上で動かして担保する
 * (issue #1104 で導入、issue #1105 で拡張)。
 */

const PNG_A = 'QUFB';
const PNG_B = 'QkJC';

interface Manifest {
  fileName: string;
}

/** 生成完了。パネルが送るのはファイル名だけの一覧(base64は含まない。issue #1105)。 */
function generated(harness: WebviewHarness, images: Manifest[]): void {
  harness.postToWebview({ command: 'generated', payload: images });
}

function image(fileName: string, dataBase64: string, mimeType = 'image/png') {
  return { fileName, dataBase64, mimeType };
}

function requestedIndices(harness: WebviewHarness): number[] {
  return harness.posted
    .filter((message) => message.command === 'requestImage')
    .map((message) => message.index as number);
}

/**
 * パネル側の応答を代行する。Webviewが要求した位置の画像データだけを、要求された順に返す。
 * 一度応じた要求は取り除き、同じ要求へ二度応じないようにする。
 */
function serveImageRequests(harness: WebviewHarness, images: Record<number, ReturnType<typeof image>>): void {
  for (let i = harness.posted.length - 1; i >= 0; i -= 1) {
    const message = harness.posted[i];
    if (message.command !== 'requestImage') continue;
    harness.posted.splice(i, 1);
    const index = message.index as number;
    const data = images[index];
    if (!data) continue;
    harness.postToWebview({ command: 'imageData', payload: { index, ...data } });
  }
}

/** サムネイル(ボタン)の中の<img>。 */
function thumbnailImage(harness: WebviewHarness, index: number): FakeElement {
  return harness.element('thumbnailStrip').children[index].children[0];
}

function thumbnailsWithData(harness: WebviewHarness): number {
  return harness.element('thumbnailStrip').children.filter((thumbnail) => thumbnail.children[0].src !== '')
    .length;
}

/**
 * 生成オプションの取得が終わった状態にする。読み込み中はLetsBlogLoadingが走っており、
 * 生成・チャットの二重実行が抑止される(実際のパネルもoptions受信までは同じ)。
 */
function ready(harness: WebviewHarness): void {
  harness.postToWebview({
    command: 'options',
    payload: { samplers: [], schedulers: [], checkpoints: [], loras: [] },
  });
}

let harness: WebviewHarness;

beforeEach(() => {
  harness = loadWebview('imageGen');
  ready(harness);
});

describe('imageGenのフォーム (issue #1105)', () => {
  it('batch sizeの上限が16である', () => {
    const input = harness.element('batchSize');

    expect(input.getAttribute('min')).toBe('1');
    expect(input.getAttribute('max')).toBe('16');
    expect(input.getAttribute('value')).toBe('1');
  });

  it('batch count入力があり、上限が16・既定が1である', () => {
    const input = harness.element('batchCount');

    expect(input).toBeDefined();
    expect(input.getAttribute('type')).toBe('number');
    expect(input.getAttribute('min')).toBe('1');
    expect(input.getAttribute('max')).toBe('16');
    expect(input.getAttribute('value')).toBe('1');
  });

  it('生成リクエストにbatchSizeとbatchCountが含まれる', () => {
    harness.element('prompt').value = '猫';
    harness.element('batchSize').value = '2';
    harness.element('batchCount').value = '3';

    harness.element('generateButton').click();

    const message = harness.posted[harness.posted.length - 1];
    expect(message.command).toBe('generate');
    expect(message.params).toMatchObject({ batchSize: 2, batchCount: 3 });
  });

  it('batch countを既定のままにすると1を送る', () => {
    harness.element('prompt').value = '犬';
    harness.element('batchSize').value = '4';

    harness.element('generateButton').click();

    const message = harness.posted[harness.posted.length - 1];
    expect(message.params).toMatchObject({ batchSize: 4, batchCount: 1 });
  });

  it('生成中のローディング表示に要求総枚数と長時間になりうる旨が出る', () => {
    harness.element('prompt').value = '猫';
    harness.element('batchSize').value = '2';
    harness.element('batchCount').value = '3';

    harness.element('generateButton').click();

    const begun = harness.loading.begins[harness.loading.begins.length - 1] as { text: string };
    expect(begun.text).toContain('6枚');
    expect(begun.text).toContain('長い時間');
  });

  /**
   * 画像は`batch_count`を持たない(#1102の方針)ため、生成設定のプリフィルでは
   * batch countを引き継がず1へ戻す。前回の回数が黙って残ると、意図しない枚数を生成してしまう。
   */
  it('プリフィルはbatch sizeを反映するがbatch countは1へ戻す', () => {
    harness.element('batchCount').value = '5';

    harness.postToWebview({ command: 'prefill', payload: { prompt: '猫', batchSize: 3, batchCount: 7 } });

    expect(harness.element('batchSize').value).toBe('3');
    expect(harness.element('batchCount').value).toBe('1');
  });
});

describe('imageGenのプレビュー', () => {
  it('2枚返るとサムネイルが2枚並び、1枚目が既定で選択される', () => {
    generated(harness, [{ fileName: 'a.png' }, { fileName: 'b.png' }]);
    serveImageRequests(harness, { 0: image('a.png', PNG_A) });

    const strip = harness.element('thumbnailStrip');
    expect(strip.children).toHaveLength(2);
    expect(strip.style.display).not.toBe('none');
    expect(strip.children[0].getAttribute('aria-pressed')).toBe('true');
    expect(strip.children[1].getAttribute('aria-pressed')).toBe('false');
    expect(harness.element('previewImage').src).toBe(`data:image/png;base64,${PNG_A}`);
    expect(harness.element('previewSection').style.display).toBe('block');
  });

  it('2枚目のサムネイルを選ぶとプレビューと選択状態が2枚目へ移る', () => {
    generated(harness, [{ fileName: 'a.png' }, { fileName: 'b.png' }]);
    serveImageRequests(harness, { 0: image('a.png', PNG_A) });

    harness.element('thumbnailStrip').children[1].click();
    serveImageRequests(harness, { 1: image('b.png', PNG_B) });

    const strip = harness.element('thumbnailStrip');
    expect(strip.children[0].getAttribute('aria-pressed')).toBe('false');
    expect(strip.children[1].getAttribute('aria-pressed')).toBe('true');
    expect(harness.element('previewImage').src).toBe(`data:image/png;base64,${PNG_B}`);
    expect(harness.element('previewInfo').textContent).toContain('b.png');
  });

  it('読み込み済みのサムネイルを選び直すと再要求せずプレビューへ戻す', () => {
    generated(harness, [{ fileName: 'a.png' }, { fileName: 'b.png' }]);
    harness.intersect(harness.element('thumbnailStrip').children);
    serveImageRequests(harness, { 0: image('a.png', PNG_A), 1: image('b.png', PNG_B) });

    harness.element('thumbnailStrip').children[1].click();
    harness.element('thumbnailStrip').children[0].click();

    expect(requestedIndices(harness)).toEqual([]);
    expect(harness.element('previewImage').src).toBe(`data:image/png;base64,${PNG_A}`);
  });

  it('2枚目を選んだ状態のアイキャッチ設定は2枚目の位置だけを送る', () => {
    generated(harness, [{ fileName: 'a.png' }, { fileName: 'b.png' }]);
    harness.element('thumbnailStrip').children[1].click();

    harness.element('setAsEyecatchButton').click();

    const message = harness.posted[harness.posted.length - 1];
    expect(message).toEqual({ command: 'setAsEyecatch', index: 1 });
  });

  it('1枚目を選んだ状態のアセット追加は1枚目の位置だけを送る', () => {
    generated(harness, [{ fileName: 'a.png' }, { fileName: 'b.png' }]);

    harness.element('addAsAssetButton').click();

    const message = harness.posted[harness.posted.length - 1];
    expect(message).toEqual({ command: 'addAsAsset', index: 0 });
  });

  it('1枚だけ返るとサムネイル列は出さず、従来どおり1枚のプレビューから保存できる', () => {
    generated(harness, [{ fileName: 'only.png' }]);
    serveImageRequests(harness, { 0: image('only.png', PNG_A) });

    expect(harness.element('thumbnailStrip').children).toHaveLength(0);
    expect(harness.element('thumbnailStrip').style.display).toBe('none');
    expect(harness.element('previewImage').src).toBe(`data:image/png;base64,${PNG_A}`);
    expect(harness.element('previewInfo').textContent).toContain('only.png');
    expect(harness.element('setAsEyecatchButton').focused).toBe(true);

    harness.element('setAsEyecatchButton').click();
    expect(harness.posted[harness.posted.length - 1]).toEqual({ command: 'setAsEyecatch', index: 0 });
  });

  it('既知でないmimeTypeはimage/pngとして扱う', () => {
    generated(harness, [{ fileName: 'x.bin' }]);
    serveImageRequests(harness, { 0: image('x.bin', PNG_A, 'text/html') });

    expect(harness.element('previewImage').src).toBe(`data:image/png;base64,${PNG_A}`);
  });

  it('生成結果が空ならエラーを出し、サムネイル列を隠す', () => {
    generated(harness, []);

    expect(harness.element('thumbnailStrip').style.display).toBe('none');
    expect(harness.element('message').textContent).toContain('生成結果が空');
    harness.element('setAsEyecatchButton').click();
    expect(harness.posted.some((m) => m.command === 'setAsEyecatch')).toBe(false);
  });

  it('生成前に保存ボタンを押しても何も送らない', () => {
    harness.posted.length = 0;

    harness.element('setAsEyecatchButton').click();
    harness.element('addAsAssetButton').click();

    expect(harness.posted).toEqual([]);
  });

  it('保存時に送るのはコマンドと選択位置だけで、画像データは送り返さない', () => {
    generated(harness, [{ fileName: 'a.png' }, { fileName: 'b.png' }]);
    serveImageRequests(harness, { 0: image('a.png', PNG_A) });

    harness.element('setAsEyecatchButton').click();

    const message = harness.posted[harness.posted.length - 1];
    expect(Object.keys(message).sort()).toEqual(['command', 'index']);
    expect(JSON.stringify(message)).not.toContain(PNG_A);
  });
});

/**
 * 256枚(batch size 16 × batch count 16)を扱える転送方式であることの検証(issue #1105)。
 *
 * #1104までは全画像のbase64を1つの`generated`メッセージで送っていた。max=4なら有界だが、
 * 1920×1080のPNGは1枚1MBを超えるため、256枚では数十MBがWebview境界を一度に越える。
 */
describe('imageGenの画像転送 (issue #1105)', () => {
  /** 1920×1080 PNGのbase64に相当する大きさ(約1MB)。 */
  const ONE_MEGABYTE_BASE64 = 'A'.repeat(1_000_000);
  const TOTAL = 256;

  function manifestOf(count: number): Manifest[] {
    return Array.from({ length: count }, (_, index) => ({ fileName: `image-${index}.png` }));
  }

  it('256枚でも生成完了メッセージにbase64を含めない', () => {
    generated(harness, manifestOf(TOTAL));

    expect(harness.element('thumbnailStrip').children).toHaveLength(TOTAL);
    // 送られてくるのはファイル名だけ。256枚でも数十KBに収まる。
    const payloadBytes = JSON.stringify(manifestOf(TOTAL)).length;
    expect(payloadBytes).toBeLessThan(20_000);
  });

  it('生成直後にDOMへ載る画像は選択中の1枚だけで、残りは要求もしない', () => {
    generated(harness, manifestOf(TOTAL));

    expect(requestedIndices(harness)).toEqual([0]);
    expect(thumbnailsWithData(harness)).toBe(0);
  });

  it('画面内に入ったサムネイルだけが画像データを要求する', () => {
    generated(harness, manifestOf(TOTAL));
    harness.posted.length = 0;

    harness.intersect([harness.element('thumbnailStrip').children[5]]);

    expect(requestedIndices(harness)).toEqual([5]);
  });

  it('画面外へ出たままのサムネイルは要求しない', () => {
    generated(harness, manifestOf(TOTAL));
    harness.posted.length = 0;

    harness.intersect([harness.element('thumbnailStrip').children[7]], false);

    expect(requestedIndices(harness)).toEqual([]);
  });

  it('同じサムネイルが二度画面に入っても要求は一度だけ', () => {
    generated(harness, manifestOf(TOTAL));
    harness.posted.length = 0;
    const thumbnail = harness.element('thumbnailStrip').children[3];

    harness.intersect([thumbnail]);
    harness.intersect([thumbnail]);

    expect(requestedIndices(harness)).toEqual([3]);
  });

  it('要求した1枚ぶんだけがサムネイルへ載る', () => {
    generated(harness, manifestOf(TOTAL));
    harness.intersect([harness.element('thumbnailStrip').children[9]]);

    serveImageRequests(harness, { 9: image('image-9.png', PNG_B) });

    expect(thumbnailImage(harness, 9).src).toBe(`data:image/png;base64,${PNG_B}`);
    expect(thumbnailImage(harness, 8).src).toBe('');
  });

  /**
   * 全256枚を順に見ていっても、DOMに同時に載る画像枚数は上限で頭打ちになる。
   * 1枚1MB相当の実サイズで流し、上限を超えて溜まらないことを固定する。
   */
  it('256枚を順に見てもDOMへ同時に載る画像は上限を超えない', () => {
    generated(harness, manifestOf(TOTAL));
    const strip = harness.element('thumbnailStrip');

    for (let index = 0; index < TOTAL; index += 1) {
      harness.intersect([strip.children[index]]);
      serveImageRequests(harness, {
        [index]: image(`image-${index}.png`, ONE_MEGABYTE_BASE64),
      });
    }

    expect(thumbnailsWithData(harness)).toBeLessThanOrEqual(24);
    // 選択中(1枚目)の画像は追い出さない。プレビューが空になってしまうため。
    expect(thumbnailImage(harness, 0).src).not.toBe('');
  });

  it('選択中の画像が最古でも追い出されず、次に古いものが追い出される', () => {
    generated(harness, manifestOf(TOTAL));
    const strip = harness.element('thumbnailStrip');
    for (let index = 0; index < 26; index += 1) {
      harness.intersect([strip.children[index]]);
      serveImageRequests(harness, { [index]: image(`image-${index}.png`, PNG_A) });
    }

    expect(thumbnailImage(harness, 0).src).not.toBe('');
    expect(thumbnailImage(harness, 1).src).toBe('');
    expect(thumbnailImage(harness, 25).src).not.toBe('');
  });

  it('選択を移した後の追い出しは最古から順に行われる', () => {
    generated(harness, manifestOf(TOTAL));
    const strip = harness.element('thumbnailStrip');
    for (let index = 0; index < 24; index += 1) {
      harness.intersect([strip.children[index]]);
      serveImageRequests(harness, { [index]: image(`image-${index}.png`, PNG_A) });
    }

    strip.children[23].click();
    harness.intersect([strip.children[30]]);
    serveImageRequests(harness, { 30: image('image-30.png', PNG_A) });

    expect(thumbnailImage(harness, 0).src).toBe('');
    expect(thumbnailImage(harness, 23).src).not.toBe('');
  });

  it('追い出された画像を選び直すと再要求してプレビューへ戻す', () => {
    generated(harness, manifestOf(TOTAL));
    const strip = harness.element('thumbnailStrip');
    for (let index = 1; index <= 26; index += 1) {
      harness.intersect([strip.children[index]]);
      serveImageRequests(harness, { [index]: image(`image-${index}.png`, PNG_A) });
    }
    harness.posted.length = 0;

    strip.children[1].click();

    expect(requestedIndices(harness)).toEqual([1]);
    serveImageRequests(harness, { 1: image('image-1.png', PNG_B) });
    expect(harness.element('previewImage').src).toBe(`data:image/png;base64,${PNG_B}`);
  });

  it('再生成すると前回の画像がプレビューに残らない', () => {
    generated(harness, [{ fileName: 'a.png' }]);
    serveImageRequests(harness, { 0: image('a.png', PNG_A) });

    generated(harness, [{ fileName: 'x.png' }, { fileName: 'y.png' }]);

    expect(harness.element('previewImage').src).toBe('');
    expect(harness.element('thumbnailStrip').children).toHaveLength(2);
  });
});

describe('imageGenのキー操作とチャット', () => {
  it('Ctrl+Enterで生成を送る', () => {
    harness.element('prompt').value = '猫';

    harness.pressKey({ key: 'Enter', ctrlKey: true });

    expect(harness.posted.some((m) => m.command === 'generate')).toBe(true);
  });

  it('promptが空のままCtrl+Enterを押しても生成を送らない', () => {
    harness.pressKey({ key: 'Enter', ctrlKey: true });

    expect(harness.posted.some((m) => m.command === 'generate')).toBe(false);
    expect(harness.element('message').textContent).toContain('promptを入力してください');
  });

  it('実行中のEscapeはキャンセルを送る', () => {
    harness.element('prompt').value = '猫';
    harness.element('generateButton').click();
    harness.posted.length = 0;

    harness.pressKey({ key: 'Escape' });

    expect(harness.posted[harness.posted.length - 1]).toEqual({ command: 'cancel' });
  });

  it('チャットからプロンプトを生成するとprompt欄へ反映される', () => {
    harness.element('chatInput').value = '夕焼けの猫';

    harness.element('sendChatButton').click();
    harness.postToWebview({ command: 'promptGenerated', payload: { prompt: 'a cat at sunset' } });

    expect(harness.posted.some((m) => m.command === 'sendChat')).toBe(true);
    expect(harness.element('prompt').value).toBe('a cat at sunset');
  });
});
