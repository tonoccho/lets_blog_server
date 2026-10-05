import {
  describeMissingAccounts,
  findMissingAccounts,
  missingSeedEnv,
  resolveAcceptanceResetMode,
  shouldCheckSyntheticAccounts,
} from './acceptance-accounts';

/**
 * #1634: 合成アカウント(e2e-test@ / e2e-admin@)の事前確認の判定ロジック。
 *
 * 受け入れ基準が「Playwright の起動前に global-setup が落ちる」ことであり、
 * Gherkin のシナリオが始まる前の振る舞いのため、純関数として単体で検証する
 * (e2e の既存単体テスト browser-prerequisite.test 系と同じ方式)。
 */
describe('shouldCheckSyntheticAccounts', () => {
  it.each([
    // [at-seed を含むか, needsSetup, 確認するか]
    [false, false, true],
    [false, true, false],
    [true, false, false],
    [true, true, false],
    [false, undefined, false],
  ])('executesSeed=%s needsSetup=%s -> %s', (executesSeed, needsSetup, expected) => {
    expect(
      shouldCheckSyntheticAccounts({
        executesSeed: executesSeed as boolean,
        needsSetup: needsSetup as boolean | undefined,
      })
    ).toBe(expected);
  });
});

describe('findMissingAccounts / describeMissingAccounts', () => {
  const both = [
    { email: 'e2e-test@letsblog.local', ok: true },
    { email: 'e2e-admin@letsblog.local', ok: true },
  ];

  it('両方取得できれば欠けていない', () => {
    expect(findMissingAccounts(both)).toEqual([]);
  });

  it('e2e-test@ だけ取得できなければ、その名前だけを返す', () => {
    const results = [{ ...both[0], ok: false }, both[1]];
    expect(findMissingAccounts(results)).toEqual(['e2e-test@letsblog.local']);
  });

  it('両方取得できなければ両方の名前を返す', () => {
    expect(findMissingAccounts(both.map((r) => ({ ...r, ok: false })))).toEqual([
      'e2e-test@letsblog.local',
      'e2e-admin@letsblog.local',
    ]);
  });

  it('メッセージに欠けているアカウント名と復元コマンドを含む', () => {
    const message = describeMissingAccounts(['e2e-test@letsblog.local']);
    expect(message).toContain('e2e-test@letsblog.local');
    expect(message).toContain('scripts/seed-acceptance-env.sh');
    expect(message).not.toContain('e2e-admin@letsblog.local');
  });
});

describe('missingSeedEnv', () => {
  it('パスワード2つがあれば、PROVISION の2変数が未設定でも足りている(seed-acceptance-env.sh のフォールバックと同じ)', () => {
    expect(missingSeedEnv({ E2E_TEST_PASSWORD: 'a', E2E_ADMIN_PASSWORD: 'b' })).toEqual([]);
  });

  it('パスワードが欠けていればその名前を返す', () => {
    expect(missingSeedEnv({ E2E_TEST_PASSWORD: 'a' })).toEqual(['E2E_ADMIN_PASSWORD']);
    expect(missingSeedEnv({ E2E_ADMIN_PASSWORD: 'b' })).toEqual(['E2E_TEST_PASSWORD']);
    expect(missingSeedEnv({})).toEqual(['E2E_TEST_PASSWORD', 'E2E_ADMIN_PASSWORD']);
  });
});

describe('resolveAcceptanceResetMode', () => {
  it.each([
    ['1', 'rebuild'],
    ['data', 'data'],
    ['', 'none'],
    [undefined, 'none'],
    ['0', 'none'],
  ])('ACCEPTANCE_RESET=%s -> %s', (value, expected) => {
    expect(resolveAcceptanceResetMode(value)).toBe(expected);
  });
});
