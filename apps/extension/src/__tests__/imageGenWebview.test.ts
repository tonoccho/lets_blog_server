import { loadWebview, WebviewHarness } from './support/webviewDom';

/**
 * Generate Imageパネルのプレビュー(webviews/imageGen.js)の検証(issue #1104)。
 *
 * 拡張のWebviewはPlaywrightから到達できないため、受け入れ基準のうちプレビュー表示と
 * 選択に関わる部分は、実際のHTML/JSをこのDOMスタブ上で動かして担保する。
 */

const PNG_A = 'QUFB';
const PNG_B = 'QkJC';

function generated(harness: WebviewHarness, images: unknown[]): void {
  harness.postToWebview({ command: 'generated', payload: images });
}

function image(fileName: string, dataBase64: string, mimeType = 'image/png') {
  return { id: 1, fileName, dataBase64, mimeType };
}

let harness: WebviewHarness;

beforeEach(() => {
  harness = loadWebview('imageGen');
});

describe('imageGenのプレビュー', () => {
  it('2枚返るとサムネイルが2枚並び、1枚目が既定で選択される', () => {
    generated(harness, [image('a.png', PNG_A), image('b.png', PNG_B)]);

    const strip = harness.element('thumbnailStrip');
    expect(strip.children).toHaveLength(2);
    expect(strip.style.display).not.toBe('none');
    expect(strip.children[0].getAttribute('aria-pressed')).toBe('true');
    expect(strip.children[1].getAttribute('aria-pressed')).toBe('false');
    expect(harness.element('previewImage').src).toBe(`data:image/png;base64,${PNG_A}`);
    expect(harness.element('previewSection').style.display).toBe('block');
  });

  it('2枚目のサムネイルを選ぶとプレビューと選択状態が2枚目へ移る', () => {
    generated(harness, [image('a.png', PNG_A), image('b.png', PNG_B)]);

    harness.element('thumbnailStrip').children[1].click();

    const strip = harness.element('thumbnailStrip');
    expect(strip.children[0].getAttribute('aria-pressed')).toBe('false');
    expect(strip.children[1].getAttribute('aria-pressed')).toBe('true');
    expect(harness.element('previewImage').src).toBe(`data:image/png;base64,${PNG_B}`);
    expect(harness.element('previewInfo').textContent).toContain('b.png');
  });

  it('2枚目を選んだ状態のアイキャッチ設定は2枚目の位置だけを送る', () => {
    generated(harness, [image('a.png', PNG_A), image('b.png', PNG_B)]);
    harness.element('thumbnailStrip').children[1].click();

    harness.element('setAsEyecatchButton').click();

    const message = harness.posted[harness.posted.length - 1];
    expect(message).toEqual({ command: 'setAsEyecatch', index: 1 });
  });

  it('1枚目を選んだ状態のアセット追加は1枚目の位置だけを送る', () => {
    generated(harness, [image('a.png', PNG_A), image('b.png', PNG_B)]);

    harness.element('addAsAssetButton').click();

    const message = harness.posted[harness.posted.length - 1];
    expect(message).toEqual({ command: 'addAsAsset', index: 0 });
  });

  it('1枚だけ返るとサムネイル列は出さず、従来どおり1枚のプレビューから保存できる', () => {
    generated(harness, [image('only.png', PNG_A)]);

    expect(harness.element('thumbnailStrip').children).toHaveLength(0);
    expect(harness.element('thumbnailStrip').style.display).toBe('none');
    expect(harness.element('previewImage').src).toBe(`data:image/png;base64,${PNG_A}`);
    expect(harness.element('previewInfo').textContent).toContain('only.png');
    expect(harness.element('setAsEyecatchButton').focused).toBe(true);

    harness.element('setAsEyecatchButton').click();
    expect(harness.posted[harness.posted.length - 1]).toEqual({ command: 'setAsEyecatch', index: 0 });
  });

  it('既知でないmimeTypeはimage/pngとして扱う', () => {
    generated(harness, [image('x.bin', PNG_A, 'text/html')]);

    expect(harness.element('previewImage').src).toBe(`data:image/png;base64,${PNG_A}`);
  });

  it('生成前に保存ボタンを押しても何も送らない', () => {
    harness.posted.length = 0;

    harness.element('setAsEyecatchButton').click();
    harness.element('addAsAssetButton').click();

    expect(harness.posted).toEqual([]);
  });

  it('保存時に送るのはコマンドと選択位置だけで、画像データは送り返さない', () => {
    generated(harness, [image('a.png', PNG_A), image('b.png', PNG_B)]);

    harness.element('setAsEyecatchButton').click();

    const message = harness.posted[harness.posted.length - 1];
    expect(Object.keys(message).sort()).toEqual(['command', 'index']);
    expect(JSON.stringify(message)).not.toContain(PNG_A);
  });
});
