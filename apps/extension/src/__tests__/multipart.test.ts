import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { buildMultipartBody, MultipartPart } from '../multipart';

/**
 * 記事公開(publishPost)のリクエストボディを組み立てる唯一の実装(issue #942 / AT-16 Layer 2)。
 * 画像同梱の公開はサーバー側の受け取り方に強く依存するため、境界・ヘッダ・区切りの形を固定する。
 */
describe('buildMultipartBody', () => {
  let tempDir: string;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-multipart-'));
  });

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  function boundaryOf(contentType: string): string {
    const match = /boundary=(.+)$/.exec(contentType);
    if (!match) throw new Error(`boundaryがありません: ${contentType}`);
    return match[1];
  }

  it('テキストフィールドをContent-Disposition付きのパートとして並べる', () => {
    const { body, contentType } = buildMultipartBody([
      { kind: 'field', name: 'site', value: 'example' },
      { kind: 'field', name: 'title', value: '日本語のタイトル' },
    ]);
    const boundary = boundaryOf(contentType);
    const text = body.toString('utf-8');

    expect(contentType.startsWith('multipart/form-data; boundary=----LetsBlogFormBoundary')).toBe(true);
    expect(text).toContain(`--${boundary}\r\nContent-Disposition: form-data; name="site"\r\n\r\nexample\r\n`);
    expect(text).toContain('name="title"\r\n\r\n日本語のタイトル\r\n');
    expect(text.endsWith(`--${boundary}--\r\n`)).toBe(true);
  });

  it('同じ名前のフィールドを複数回送れる(カテゴリ・タグの複数指定)', () => {
    const { body } = buildMultipartBody([
      { kind: 'field', name: 'tags', value: 'a' },
      { kind: 'field', name: 'tags', value: 'b' },
    ]);
    const text = body.toString('utf-8');
    expect(text.match(/name="tags"/g)).toHaveLength(2);
  });

  it('ファイルパートは中身をそのまま載せ、指定のContent-Typeを付ける', () => {
    const filePath = path.join(tempDir, 'eyecatch.png');
    const content = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x00, 0xff]);
    fs.writeFileSync(filePath, content);

    const { body } = buildMultipartBody([
      { kind: 'file', name: 'images', filename: 'assets/eyecatch.png', filePath, contentType: 'image/png' },
    ]);

    const text = body.toString('binary');
    expect(text).toContain('Content-Disposition: form-data; name="images"; filename="assets/eyecatch.png"');
    expect(text).toContain('Content-Type: image/png');
    expect(body.includes(content)).toBe(true);
  });

  it('Content-Type未指定のファイルはapplication/octet-streamになる', () => {
    const filePath = path.join(tempDir, 'data.bin');
    fs.writeFileSync(filePath, 'x');

    const { body } = buildMultipartBody([
      { kind: 'file', name: 'images', filename: 'data.bin', filePath },
    ]);

    expect(body.toString('utf-8')).toContain('Content-Type: application/octet-stream');
  });

  it('ヘッダを壊す引用符と改行をファイル名から取り除く', () => {
    const filePath = path.join(tempDir, 'quoted.png');
    fs.writeFileSync(filePath, 'x');

    const { body } = buildMultipartBody([
      { kind: 'file', name: 'ima\r\nges', filename: 'a"b\r\nc.png', filePath },
    ]);

    const header = body.toString('utf-8').split('\r\n\r\n')[0];
    expect(header).toContain('name="images"');
    expect(header).toContain('filename="a%22bc.png"');
  });

  it('パートが無い場合でも終端の境界だけを返す', () => {
    const { body, contentType } = buildMultipartBody([]);
    expect(body.toString('utf-8')).toBe(`--${boundaryOf(contentType)}--\r\n`);
  });

  it('呼び出しごとに異なる境界を使う(本文と境界の衝突を避けるため)', () => {
    const first = buildMultipartBody([]);
    const second = buildMultipartBody([]);
    expect(first.contentType).not.toBe(second.contentType);
  });
});
