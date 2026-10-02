import { execFileSync } from 'node:child_process';
import { crc32, inflateRawSync } from 'node:zlib';
import type { APIRequestContext, Page } from '@playwright/test';
import { kcadm, kcadmLogin, KEYCLOAK_REALM } from '../kcadm';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { waitForHydrated } from '../support/responseBudgetFixtures';

/**
 * バックアップからのリストア(backup-restore.feature)を支えるステップ定義
 * (issue #1157 / 親issue #940 シナリオ14)。
 *
 * バックアップの取得は `backup.steps.ts` の「管理者としてバックアップの作成をダウンロードする」
 * (`ctx.backupBytes`)を再利用し、ここでは重複して定義しない。
 *
 * 代表データ(利用者の決定 2026-10-02。feature 冒頭の説明を参照):
 *   - project-service: プロジェクト3つ(P1=名前を変更 / P2=削除 / P3=バックアップ後に追加)
 *   - content-service: P1 のカスタムタグ2つ(T1=説明・テンプレートを変更 / T2=削除)
 *   - Keycloak PostgreSQL: テスト用ユーザー(姓名を変更)
 */

const ORIGINAL = {
  projectKeep: 'ORIGINAL-KEEP',
  projectDelete: 'ORIGINAL-DELETE',
  tagDescription: 'ORIGINAL-DESC',
  tagTemplate: '<div class="orig-1157">ORIGINAL-BODY {{content}}</div>',
  keycloakFirstName: 'RestoreOriginal',
} as const;

const CHANGED = {
  projectKeep: 'CHANGED-KEEP',
  tagDescription: 'CHANGED-DESC',
  tagTemplate: '<div class="changed-1157">CHANGED-BODY {{content}}</div>',
  keycloakFirstName: 'RestoreChanged',
} as const;

/** 失敗させるMySQLスキーマ。lbs_project / lbs_content より後、Keycloak PostgreSQL より前に復元される。 */
const BROKEN_SCHEMA = 'lbs_media';
const RESTORE_TIMEOUT_MS = 300_000;

interface RestoreFixture {
  suffix: string;
  keep: { id: number; name: string };
  doomed: { id: number; name: string };
  added?: { id: number };
  tagChanged: { id: number; tagName: string };
  tagDeleted: { id: number; tagName: string };
  keycloakUserId: string;
  keycloakEmail: string;
}

async function authHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}` };
}

function fixtureOf(ctx: Record<string, unknown>): RestoreFixture {
  const fixture = ctx.restoreFixture as RestoreFixture | undefined;
  if (!fixture) {
    throw new Error('復元検証用の代表データが作成されていません');
  }
  return fixture;
}

async function expectOk(response: { ok(): boolean; status(): number; text(): Promise<string> }, what: string) {
  expect(response.ok(), `${what}に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
}

async function createProject(request: APIRequestContext, name: string, slug: string): Promise<number> {
  const response = await request.post('/api/projects', { headers: await authHeaders(request), data: { name, slug } });
  await expectOk(response, `プロジェクト ${name} の作成`);
  return ((await response.json()) as { id: number }).id;
}

async function createTag(
  request: APIRequestContext, projectId: number, tagName: string
): Promise<number> {
  const response = await request.post('/api/custom-tags', {
    headers: await authHeaders(request),
    data: {
      tagName,
      htmlTemplate: ORIGINAL.tagTemplate,
      description: ORIGINAL.tagDescription,
      tagFormat: 'BLOCK',
      projectId,
    },
  });
  await expectOk(response, `カスタムタグ ${tagName} の作成`);
  return ((await response.json()) as { id: number }).id;
}

function findKeycloakUserId(email: string): string | undefined {
  kcadmLogin();
  const users = JSON.parse(
    kcadm(['get', 'users', '-r', KEYCLOAK_REALM, '-q', `email=${email}`, '--fields', 'id'])
  ) as { id: string }[];
  return users[0]?.id;
}

function keycloakFirstName(userId: string): string {
  kcadmLogin();
  const user = JSON.parse(kcadm(['get', `users/${userId}`, '-r', KEYCLOAK_REALM, '--fields', 'firstName'])) as {
    firstName?: string;
  };
  return user.firstName ?? '';
}

/**
 * 復元後の確認用。復元の効果は Keycloak PostgreSQL に現れるため、DB を直接読む。
 * Keycloak の API(kcadm)は復元後もキャッシュ済みの値を返すことがあり(別 Issue で起票)、
 * 復元そのものの検証には使えない。
 */
function keycloakFirstNameInDb(userId: string): string {
  return execFileSync(
    'docker',
    ['exec', 'lbs-keycloak-postgres', 'psql', '-U', 'keycloak', '-d', 'keycloak', '-tA',
      '-c', `select first_name from user_entity where id='${userId}'`],
    { encoding: 'utf8' }
  ).trim();
}

// Cucumber expression では `(` `)` が省略可能テキストの構文のため、リテラルとして使うにはエスケープする。
Given('復元検証用の代表データ\\(プロジェクト・カスタムタグ・Keycloakユーザー\\)を作成する', async ({ ctx, request }) => {
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const keepId = await createProject(request, `E2E restore ${ORIGINAL.projectKeep} ${suffix}`, `e2e-1157-keep-${suffix}`);
  const doomedId = await createProject(
    request, `E2E restore ${ORIGINAL.projectDelete} ${suffix}`, `e2e-1157-del-${suffix}`
  );
  const tagChangedName = `e2e1157a${suffix}`;
  const tagDeletedName = `e2e1157b${suffix}`;
  const tagChangedId = await createTag(request, keepId, tagChangedName);
  const tagDeletedId = await createTag(request, keepId, tagDeletedName);

  const keycloakEmail = `e2e-1157-restore-${suffix}@example.com`;
  kcadmLogin();
  kcadm([
    'create', 'users', '-r', KEYCLOAK_REALM,
    '-s', `username=${keycloakEmail}`, '-s', `email=${keycloakEmail}`,
    '-s', 'enabled=true', '-s', 'emailVerified=true',
    '-s', `firstName=${ORIGINAL.keycloakFirstName}`, '-s', 'lastName=E2E1157',
  ]);
  const keycloakUserId = findKeycloakUserId(keycloakEmail);
  expect(keycloakUserId, 'Keycloakのテスト用ユーザーを作成できませんでした').toBeTruthy();

  ctx.restoreFixture = {
    suffix,
    keep: { id: keepId, name: `E2E restore ${ORIGINAL.projectKeep} ${suffix}` },
    doomed: { id: doomedId, name: `E2E restore ${ORIGINAL.projectDelete} ${suffix}` },
    tagChanged: { id: tagChangedId, tagName: tagChangedName },
    tagDeleted: { id: tagDeletedId, tagName: tagDeletedName },
    keycloakUserId: keycloakUserId as string,
    keycloakEmail,
  } satisfies RestoreFixture;
});

Given(
  '復元検証用の代表データをバックアップ後の状態へ変更・削除し、新しいプロジェクトを追加する',
  async ({ ctx, request }) => {
    const fixture = fixtureOf(ctx);
    const headers = await authHeaders(request);

    await expectOk(
      await request.put(`/api/projects/${fixture.keep.id}`, {
        headers, data: { name: `E2E restore ${CHANGED.projectKeep} ${fixture.suffix}` },
      }),
      'プロジェクト名の変更'
    );
    await expectOk(await request.delete(`/api/projects/${fixture.doomed.id}`, { headers }), 'プロジェクトの削除');
    fixture.added = {
      id: await createProject(
        request, `E2E restore ADDED ${fixture.suffix}`, `e2e-1157-added-${fixture.suffix}`
      ),
    };

    await expectOk(
      await request.put(`/api/custom-tags/${fixture.tagChanged.id}`, {
        headers,
        data: {
          tagName: fixture.tagChanged.tagName,
          htmlTemplate: CHANGED.tagTemplate,
          description: CHANGED.tagDescription,
          tagFormat: 'BLOCK',
          projectId: fixture.keep.id,
        },
      }),
      'カスタムタグの変更'
    );
    await expectOk(await request.delete(`/api/custom-tags/${fixture.tagDeleted.id}`, { headers }), 'カスタムタグの削除');

    kcadmLogin();
    kcadm([
      'update', `users/${fixture.keycloakUserId}`, '-r', KEYCLOAK_REALM,
      '-s', `firstName=${CHANGED.keycloakFirstName}`,
    ]);
    expect(keycloakFirstName(fixture.keycloakUserId), 'Keycloakユーザーの変更が反映されていません')
      .toBe(CHANGED.keycloakFirstName);
  }
);

// ---- 不正なアーカイブの作成(zipをNode標準だけで読み書きする。backup.steps.tsと同じ方針) ----

interface ZipEntry {
  name: string;
  data: Buffer;
}

/** セントラルディレクトリを辿り、全エントリを展開して読む。順序は保つ(復元順がエントリ順のため)。 */
function readZipEntries(bytes: Buffer): ZipEntry[] {
  const eocd = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd === -1) {
    throw new Error('zipのEnd Of Central Directoryレコードが見つかりませんでした');
  }
  const count = bytes.readUInt16LE(eocd + 10);
  let cursor = bytes.readUInt32LE(eocd + 16);
  const entries: ZipEntry[] = [];
  for (let i = 0; i < count; i += 1) {
    if (bytes.readUInt32LE(cursor) !== 0x02014b50) {
      throw new Error(`zipのセントラルディレクトリのヘッダが不正です(offset=${cursor})`);
    }
    const method = bytes.readUInt16LE(cursor + 10);
    const compressedSize = bytes.readUInt32LE(cursor + 20);
    const nameLength = bytes.readUInt16LE(cursor + 28);
    const extraLength = bytes.readUInt16LE(cursor + 30);
    const commentLength = bytes.readUInt16LE(cursor + 32);
    const localOffset = bytes.readUInt32LE(cursor + 42);
    const name = bytes.toString('utf-8', cursor + 46, cursor + 46 + nameLength);
    const localNameLength = bytes.readUInt16LE(localOffset + 26);
    const localExtraLength = bytes.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const raw = bytes.subarray(dataStart, dataStart + compressedSize);
    entries.push({ name, data: method === 8 ? inflateRawSync(raw) : Buffer.from(raw) });
    cursor += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

/** 全エントリを無圧縮(stored)で書き出す。`metadata.json` を含む他のエントリは元のまま。 */
function writeZipEntries(entries: ZipEntry[]): Buffer {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf-8');
    const crc = crc32(entry.data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(entry.data.length, 18);
    local.writeUInt32LE(entry.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    locals.push(local, name, entry.data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(entry.data.length, 20);
    central.writeUInt32LE(entry.data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, name);
    offset += 30 + name.length + entry.data.length;
  }
  const centralBuffer = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralBuffer.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBuffer, eocd]);
}

function breakSchemaDump(archive: Buffer, schema: string): Buffer {
  const entries = readZipEntries(archive);
  const target = `mysql/${schema}.sql`;
  expect(entries.map((e) => e.name), `元のアーカイブに ${target} がありません`).toContain(target);
  return writeZipEntries(
    entries.map((e) => (e.name === target ? { name: e.name, data: Buffer.from('THIS IS NOT VALID SQL;\n') } : e))
  );
}

// ---- バックアップ画面からの復元 ----

async function restoreFromBackupScreen(page: Page, archive: Buffer): Promise<void> {
  await page.goto('/admin/backup');
  await expect(page.getByRole('button', { name: 'リストアを実行' })).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(page.locator('form').first());
  await page.locator('input[type="file"][name="file"]').setInputFiles({
    name: 'backup.zip',
    mimeType: 'application/zip',
    buffer: archive,
  });
  page.once('dialog', (dialog) => void dialog.accept());
  await page.getByRole('button', { name: 'リストアを実行' }).click();
  // 成功(緑)か失敗(赤)のどちらかの表示が出るまで待つ。結果の判定は後続のステップが行う。
  await expect(page.locator('form p.text-green-600, form p.text-red-600')).toBeVisible({
    timeout: RESTORE_TIMEOUT_MS,
  });
}

When('復元検証用に取得したバックアップを、バックアップ画面から復元する', async ({ page, ctx, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + RESTORE_TIMEOUT_MS);
  await restoreFromBackupScreen(page, ctx.backupBytes as Buffer);
});

When('復元検証用に取得した正しいバックアップを、バックアップ画面から復元する', async ({ page, ctx, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + RESTORE_TIMEOUT_MS);
  await restoreFromBackupScreen(page, ctx.backupBytes as Buffer);
});

When(
  `復元検証用に取得したバックアップのうち ${BROKEN_SCHEMA} のダンプだけを不正なSQLに差し替えたアーカイブを、バックアップ画面から復元する`,
  async ({ page, ctx, $testInfo }) => {
    $testInfo.setTimeout($testInfo.timeout + RESTORE_TIMEOUT_MS);
    await restoreFromBackupScreen(page, breakSchemaDump(ctx.backupBytes as Buffer, BROKEN_SCHEMA));
  }
);

Then('画面に「リストアが完了しました。」と表示される', async ({ page }) => {
  await expect(page.getByText('リストアが完了しました。')).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('form p.text-red-600'), '成功表示と同時にエラーが表示されています').toHaveCount(0);
});

Then(
  `画面にエラーが表示され、そのメッセージに失敗したスキーマ名 ${BROKEN_SCHEMA} が含まれる`,
  async ({ page }) => {
    const error = page.locator('form p.text-red-600');
    await expect(error).toBeVisible({ timeout: 30_000 });
    await expect(error).toContainText(BROKEN_SCHEMA);
    await expect(page.getByText('リストアが完了しました。')).toHaveCount(0);
  }
);

// ---- 復元後の状態の確認 ----

async function projectStatus(request: APIRequestContext, id: number): Promise<{ status: number; name?: string }> {
  const response = await request.get(`/api/projects/${id}`, { headers: await authHeaders(request) });
  return response.ok()
    ? { status: response.status(), name: ((await response.json()) as { name: string }).name }
    : { status: response.status() };
}

async function expectMysqlRepresentativeDataAtBackupTime(
  request: APIRequestContext, fixture: RestoreFixture
): Promise<void> {
  const keep = await projectStatus(request, fixture.keep.id);
  expect(keep.name, 'バックアップ後に名前を変えたプロジェクトがバックアップ時点の名前に戻っていません').toBe(fixture.keep.name);
  const doomed = await projectStatus(request, fixture.doomed.id);
  expect(doomed.name, 'バックアップ後に削除したプロジェクトが復元されていません').toBe(fixture.doomed.name);

  const response = await request.get(`/api/projects/${fixture.keep.id}/custom-tags`, {
    headers: await authHeaders(request),
  });
  await expectOk(response, 'カスタムタグ一覧の取得');
  const tags = (await response.json()) as { id: number; description: string; htmlTemplate: string }[];
  const changed = tags.find((t) => t.id === fixture.tagChanged.id);
  expect(changed, 'バックアップ後に変更したカスタムタグが見つかりません').toBeDefined();
  expect(changed!.description).toBe(ORIGINAL.tagDescription);
  expect(changed!.htmlTemplate).toBe(ORIGINAL.tagTemplate);
  expect(
    tags.some((t) => t.id === fixture.tagDeleted.id),
    'バックアップ後に削除したカスタムタグが復元されていません'
  ).toBe(true);
}

Then('復元検証用の代表データはバックアップ時点の値で存在する', async ({ ctx, request }) => {
  const fixture = fixtureOf(ctx);
  await expectMysqlRepresentativeDataAtBackupTime(request, fixture);
  expect(
    keycloakFirstNameInDb(fixture.keycloakUserId),
    'Keycloakのテスト用ユーザーがバックアップ時点の値に戻っていません'
  ).toBe(ORIGINAL.keycloakFirstName);
});

Then('バックアップ後に追加したプロジェクトは存在しない', async ({ ctx, request }) => {
  const fixture = fixtureOf(ctx);
  const added = fixture.added;
  expect(added, 'バックアップ後に追加したプロジェクトが記録されていません').toBeDefined();
  const response = await request.get('/api/projects', { headers: await authHeaders(request) });
  await expectOk(response, 'プロジェクト一覧の取得');
  const projects = (await response.json()) as { id: number }[];
  expect(projects.some((p) => p.id === added!.id), 'バックアップ後に追加したプロジェクトが残っています').toBe(false);
});

Then(
  '失敗より前に復元されたMySQLスキーマの代表データはバックアップ時点の値のまま残っている',
  async ({ ctx, request }) => {
    await expectMysqlRepresentativeDataAtBackupTime(request, fixtureOf(ctx));
  }
);

Then(
  'Keycloakの復元は実行されておらず、テスト用ユーザーはバックアップ後に変更した値のままである',
  async ({ ctx }) => {
    expect(
      keycloakFirstNameInDb(fixtureOf(ctx).keycloakUserId),
      '失敗したスキーマより後のKeycloakの復元が実行されています'
    ).toBe(CHANGED.keycloakFirstName);
  }
);

// ---- 後始末 ----
// 復元の後、バックアップ時点の代表データ(P1/P2/T1/T2/Keycloakユーザー)が環境に残る。
// 途中で落ちても後続の段階を汚さないよう、存在するものを全て片付ける(無いものは無視する)。
After({ tags: '@destructive' }, async ({ ctx, request }) => {
  const fixture = ctx.restoreFixture as RestoreFixture | undefined;
  if (!fixture) {
    return;
  }
  const headers = await authHeaders(request);
  for (const tagId of [fixture.tagChanged.id, fixture.tagDeleted.id]) {
    await request.delete(`/api/custom-tags/${tagId}`, { headers });
  }
  for (const projectId of [fixture.keep.id, fixture.doomed.id, fixture.added?.id]) {
    if (projectId !== undefined) {
      await request.delete(`/api/projects/${projectId}`, { headers });
    }
  }
  const keycloakUserId = findKeycloakUserId(fixture.keycloakEmail);
  if (keycloakUserId) {
    kcadm(['delete', `users/${keycloakUserId}`, '-r', KEYCLOAK_REALM]);
  }
});
