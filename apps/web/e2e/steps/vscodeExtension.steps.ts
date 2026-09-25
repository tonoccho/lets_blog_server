import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * VSCode拡張の配布(vscode-extension.feature)を支えるステップ定義
 * (issue #1153 / 親issue #940 シナリオ16・17)。
 *
 * 「管理者としてログインする」は auth.steps.ts が提供する共通ステップを再利用する。
 */

const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;

/**
 * .vsix(zip)のセントラルディレクトリからエントリ名だけを読み取る。
 * このステップ定義はファイル名の一覧(存在確認・パスの接頭辞検査)にしか使わないため、
 * 展開(解凍)は不要で、圧縮方式を問わずセントラルディレクトリのヘッダのみを読めばよい。
 * 依存を増やさず、かつテストの実行環境にpython3等の外部ランタイムを要求しないよう、
 * Node標準の`Buffer`だけでzip仕様の該当部分を直接パースする。
 */
function listZipEntries(bytes: Buffer): string[] {
  const eocdOffset = bytes.lastIndexOf(
    Buffer.from([0x50, 0x4b, 0x05, 0x06])
  );
  if (eocdOffset === -1 || bytes.readUInt32LE(eocdOffset) !== EOCD_SIGNATURE) {
    throw new Error('zipのEnd Of Central Directoryレコードが見つかりませんでした');
  }
  const centralDirectoryEntryCount = bytes.readUInt16LE(eocdOffset + 10);
  const centralDirectoryOffset = bytes.readUInt32LE(eocdOffset + 16);

  const entries: string[] = [];
  let cursor = centralDirectoryOffset;
  for (let i = 0; i < centralDirectoryEntryCount; i += 1) {
    if (bytes.readUInt32LE(cursor) !== CENTRAL_DIRECTORY_SIGNATURE) {
      throw new Error(`zipのセントラルディレクトリのヘッダが不正です(offset=${cursor})`);
    }
    const fileNameLength = bytes.readUInt16LE(cursor + 28);
    const extraFieldLength = bytes.readUInt16LE(cursor + 30);
    const fileCommentLength = bytes.readUInt16LE(cursor + 32);
    const fileNameStart = cursor + 46;
    entries.push(bytes.toString('utf-8', fileNameStart, fileNameStart + fileNameLength));
    cursor = fileNameStart + fileNameLength + extraFieldLength + fileCommentLength;
  }
  return entries;
}

When('VSCode拡張をダウンロードする', async ({ ctx, page }) => {
  const response = await page.request.get('/downloads/vscode-extension');
  expect(
    response.ok(),
    `VSCode拡張のダウンロードに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.platformVsixBytes = await response.body();
});

Then(
  'ダウンロードしたファイルは有効なzipであり、extension.vsixmanifestを含む',
  async ({ ctx }) => {
    const entries = listZipEntries(ctx.platformVsixBytes as Buffer);
    expect(entries.length, 'zipとして展開できませんでした').toBeGreaterThan(0);
    expect(entries).toContain('extension.vsixmanifest');
  }
);

Then(
  /^ダウンロードしたファイルにcoverage\/やsrc\/配下のファイルは含まれない$/,
  async ({ ctx }) => {
    const entries = listZipEntries(ctx.platformVsixBytes as Buffer);
    const offending = entries.filter(
      // .vscodeignore の `coverage/**` / `src/**` はvsceのルート(vsixでは"extension/"配下)
      // からの相対パスにのみ効く。node_modules配下の依存パッケージが自前で持つ src/ ディレクトリ
      // (例: zodの"extension/node_modules/zod/src/...")は対象外であり、含まれていてよい。
      (name) =>
        /^extension\/(coverage|src)\//.test(name)
    );
    expect(offending, `除外されるべきファイルが含まれています: ${offending.join(', ')}`).toEqual([]);
  }
);
