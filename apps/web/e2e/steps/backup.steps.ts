import type { APIRequestContext } from '@playwright/test';
import { Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * バックアップの作成・ダウンロードと認可(backup.feature)を支えるステップ定義
 * (issue #1156 / 親issue #940 シナリオ13・15)。
 *
 * ## platform.steps.ts と分けている理由
 *
 * 親issue #940の分割で、システム設定・ダッシュボード状態・VSCode拡張配布は兄弟issueが
 * 引き取り、それぞれ専用のステップ定義ファイルを持つ(#1152 / #1154 / #1153)。
 * このissueもそれに倣い、このissueの範囲だけを新規ファイルへ切り出す。
 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function userToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
}

/**
 * docker-compose.ymlのplatformサービスが渡すBACKUP_MYSQL_SCHEMASと一致させること。
 * application.ymlの既定値は末尾に`lets_blog`(廃止済みlegacy-apiのスキーマ)も含むが、
 * docker-compose.ymlはそれを渡していない(このスタックには存在しないスキーマのため)。
 */
const BACKUP_MYSQL_SCHEMAS = [
  'lbs_identity',
  'lbs_project',
  'lbs_content',
  'lbs_media',
  'lbs_ai',
  'lbs_publishing',
  'lbs_analytics',
  'lbs_platform',
  'lbs_log',
];

const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;

/**
 * バックアップアーカイブ(zip)のセントラルディレクトリからエントリ名だけを読み取る。
 * このステップ定義はファイル名の一覧(スキーマ/DBダンプの存在確認)にしか使わないため、
 * 展開(解凍)は不要で、圧縮方式を問わずセントラルディレクトリのヘッダのみを読めばよい。
 * `vscodeExtension.steps.ts`と同様、外部ランタイム(python3等)に頼らずNode標準の
 * `Buffer`だけでzip仕様の該当部分を直接パースする。
 */
function listZipEntries(bytes: Buffer): string[] {
  const eocdOffset = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
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

When('管理者としてバックアップの作成をダウンロードする', async ({ ctx, request }) => {
  const response = await request.get('/api/backup/download', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    timeout: 180_000,
  });
  expect(
    response.ok(),
    `バックアップのダウンロードに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.backupBytes = await response.body();
});

Then(
  'ダウンロードしたアーカイブは有効なzipであり、全MySQLスキーマとKeycloakのダンプを含む',
  async ({ ctx }) => {
    const entries = listZipEntries(ctx.backupBytes as Buffer);
    expect(entries.length, 'zipとして展開できませんでした').toBeGreaterThan(0);
    for (const schema of BACKUP_MYSQL_SCHEMAS) {
      expect(entries, `スキーマ ${schema} のダンプが含まれていません`).toContain(`mysql/${schema}.sql`);
    }
    expect(
      entries.some((name) => name.startsWith('postgres/') && name.endsWith('.dump')),
      'KeycloakのPostgreSQLダンプが含まれていません'
    ).toBe(true);
  }
);

Then('一般ユーザーとしてバックアップのダウンロードを要求すると拒否される', async ({ request }) => {
  const response = await request.get('/api/backup/download', {
    headers: { Authorization: `Bearer ${await userToken(request)}` },
  });
  expect(response.status(), `応答本文: ${await response.text()}`).toBe(403);
});
