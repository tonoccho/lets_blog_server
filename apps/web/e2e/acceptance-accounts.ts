import type { ProjectBrowserSelection } from './browser-prerequisite';

/**
 * E2E 合成アカウント(e2e-test@ / e2e-admin@)の事前確認の判定ロジック(issue #1634)。
 *
 * リセットは `*@letsblog.local` を全部消し、作り直すのは at-seed だけである。at-seed を
 * 通さない実行(`--project=at-main --no-deps` など)でアカウントが欠けていると、個々の
 * シナリオが `invalid_grant` やログイン待ちのタイムアウトで散発的に落ち、原因が埋もれる。
 * そこで global-setup が最初のシナリオより前に確認する。副作用のある部分(HTTP)は
 * global-setup 側に置き、ここは判定だけの純関数にして単体で検証する。
 */

export const SEED_COMMAND = 'scripts/seed-acceptance-env.sh';

export type AccountCheckResult = { email: string; ok: boolean };

/** 実行対象のプロジェクトに at-seed が含まれるか。含まれるならシードが合成アカウントを作る。 */
export function executesSeedStage(executedProjects: readonly ProjectBrowserSelection[]): boolean {
  return executedProjects.some((project) => project.name === 'at-seed');
}

/**
 * 合成アカウントの存在を確認すべきか。
 *
 * - at-seed を含む実行: シードが作るので確認しない。
 * - `needsSetup: true`(リセット直後、ユーザー0人): at-setup が最初の管理者を作る前なので確認しない。
 * - `needsSetup` が読めなかった(undefined): 状態を断定できないので確認しない。
 */
export function shouldCheckSyntheticAccounts(input: {
  executesSeed: boolean;
  needsSetup: boolean | undefined;
}): boolean {
  return !input.executesSeed && input.needsSetup === false;
}

export function findMissingAccounts(results: readonly AccountCheckResult[]): string[] {
  return results.filter((result) => !result.ok).map((result) => result.email);
}

export function describeMissingAccounts(missing: readonly string[]): string {
  return (
    `E2E 合成アカウントがトークンを取得できません: ${missing.join(', ')}。\n` +
    'リセット(*@letsblog.local の全削除)のあと、at-seed を通さずに実行した可能性があります。' +
    `復元するには ${SEED_COMMAND} を実行してください` +
    '(at-setup を検証するときは npm run test:at:setup を使う。docs/ACCEPTANCE_TESTING.md §10)。'
  );
}

/**
 * シードの前提となる環境変数のうち、未設定のもの。
 * `E2E_PROVISION_ADMIN_EMAIL` / `E2E_PROVISION_ADMIN_PASSWORD` は seed-acceptance-env.sh が
 * e2e-admin@ / E2E_ADMIN_PASSWORD へフォールバックするので必須にしない(#1634)。
 */
export function missingSeedEnv(env: Record<string, string | undefined>): string[] {
  return ['E2E_TEST_PASSWORD', 'E2E_ADMIN_PASSWORD'].filter((name) => !env[name]);
}

export type AcceptanceResetMode = 'rebuild' | 'data' | 'none';

/** `ACCEPTANCE_RESET` の値: `1` はゼロ構築、`data` はデータ層リセット(scripts/run-at-setup.sh 用)。 */
export function resolveAcceptanceResetMode(value: string | undefined): AcceptanceResetMode {
  if (value === '1') return 'rebuild';
  if (value === 'data') return 'data';
  return 'none';
}
