import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

/**
 * `kcadm.sh`(Keycloak管理CLI)を`docker exec`経由で呼ぶ共有ヘルパー(issue #1328)。
 *
 * 元々は`adminSelfGuard.steps.ts`・`auth.steps.ts`・`avatarUpload.steps.ts`・
 * `bruteForceLockout.steps.ts`・`projectMember.steps.ts`・`publishAuthor.steps.ts`・
 * `roleManagement.steps.ts`・`timezoneOverride.steps.ts`・`userDeactivation.steps.ts`・
 * `userManagement.steps.ts`の10ファイルが、`kcadm`/`kcadmLogin`/`KCADM_BIN`をそれぞれ
 * 独立に複製していた(相互にimportし合っていなかった)。
 *
 * `kcadm.sh`はコンテナ内の単一ファイル(`/opt/keycloak/.keycloak/kcadm.config`)に
 * ログインセッションを保存するため、複数のPlaywrightワーカー(=複数のホストプロセス)が
 * ほぼ同時に`docker exec ... kcadm.sh`を呼ぶと"Failed to get lock on ...kcadm.config"で
 * 失敗しうる(issue #1328本文)。対策は「保険としての再試行」のみ:
 * #1295で`auth.steps.ts`にのみ入っていた"Failed to get lock"検知時の再試行(最大5回、
 * 200ms間隔)を、この共有関数に集約し、他9ファイル分の呼び出しにも揃えた。
 *
 * 【2026-09-22 差し戻し】ホスト側でのクロスプロセス直列化(`at-lock.ts`/`account-lock.ts`と
 * 同じ`flock(1)`手法)も一度実装したが、レビュー差し戻しにより不採用とした。issue #1328
 * 本文でこの直列化(Requirements 2)は元々任意(「検討する」)であり、Out of Scopeにも
 * 「必須要件としないこと」と明記されている。呼び出し元が実機で計測したところ
 * (`identity/`配下29シナリオ、既定の並列度4ワーカー、同一の共有スタック)、直列化は
 * "Failed to get lock"を1件も減らさない一方、`kcadm()`を呼ぶ全シナリオの所要時間を
 * 軒並み押し上げ、`@mode:serial`の`project-members.feature`のように先頭シナリオが
 * 時間切れになると残りのシナリオが丸ごと飛ぶ連鎖的なタイムアウトを新たに引き起こした。
 * 実害(ロック競合)を減らさずにコスト(所要時間)だけを積むため、この保険の再試行だけを
 * 唯一の対策として残す。
 */

const KEYCLOAK_CONTAINER = 'lbs-keycloak';

/** 全ファイル共通のKeycloakレルム名。呼び出し側(各steps.tsファイル)が`kcadm()`の引数に
 * そのまま渡すため、ここでも公開する。 */
export const KEYCLOAK_REALM = 'letsblog';

const KCADM_BIN = '/opt/keycloak/bin/kcadm.sh';

/** リポジトリルート(`apps/web/e2e`から3階層上)。`.env`からKeycloakのmaster管理者資格情報を読む。 */
const KCADM_REPO_ROOT = path.resolve(__dirname, '..', '..', '..');

function readEnvValue(key: string): string {
  const envPath = path.join(KCADM_REPO_ROOT, '.env');
  const content = fs.readFileSync(envPath, 'utf-8');
  const match = content.match(new RegExp(`^${key}=(.*)$`, 'm'));
  if (!match) {
    throw new Error(`.env に ${key} が見つかりません`);
  }
  return match[1].trim();
}

const KCADM_LOCK_RETRY_ATTEMPTS = 5;
const KCADM_LOCK_RETRY_DELAY_MS = 200;

/**
 * `docker exec ... kcadm.sh`本体。"Failed to get lock"検知時の再試行が唯一の対策
 * (#1295の既存対策。ホスト側でのクロスプロセス直列化は検討したが不採用 —
 * モジュール冒頭コメント「2026-09-22 差し戻し」を参照)。
 */
function execKcadmWithRetry(args: string[]): string {
  for (let attempt = 1; ; attempt += 1) {
    try {
      return execFileSync('docker', ['exec', KEYCLOAK_CONTAINER, KCADM_BIN, ...args], {
        encoding: 'utf-8',
        timeout: 30_000,
      });
    } catch (error) {
      const output = `${(error as { stdout?: string }).stdout ?? ''}${(error as { stderr?: string }).stderr ?? ''}`;
      if (attempt >= KCADM_LOCK_RETRY_ATTEMPTS || !output.includes('Failed to get lock')) {
        throw error;
      }
      execFileSync('sleep', [String(KCADM_LOCK_RETRY_DELAY_MS / 1000)]);
    }
  }
}

/**
 * `kcadm.sh`を`docker exec`経由で呼ぶ。"Failed to get lock"検知時の再試行(#1295)のみで
 * 対処する。ホスト側でのクロスプロセス直列化は行わない(2026-09-22 差し戻し。モジュール
 * 冒頭コメント参照)。
 *
 * [呼び出し単位ロックの安全性の前提] `kcadm()`は呼び出しごとに独立しており、
 * `kcadmLogin()`と直後の操作の間に別プロセスが割り込みうる。これが今安全なのは、
 * すべての`kcadmLogin()`が同一のmaster admin(`.env`の`KEYCLOAK_ADMIN_USERNAME`/
 * `KEYCLOAK_ADMIN_PASSWORD`)で認証しているため — `config credentials`と後続の操作の
 * 間に別プロセスが割り込んでも、同じ資格情報で上書きするだけで、別の資格情報で
 * 操作してしまうことはない。master以外のidentityで認証する呼び出し元を将来足す場合は、
 * loginと使用を1つの臨界区間として保持する仕組み(例: `withKcadmSession()`)が要る。
 */
export function kcadm(args: string[]): string {
  return execKcadmWithRetry(args);
}

/** `kcadm.sh`のmasterレルム管理者としてログインする(セッションはコンテナ内ファイルに保存)。
 * 呼び出し単位ロックの安全性の前提は`kcadm()`のコメントを参照。 */
export function kcadmLogin(): void {
  const username = readEnvValue('KEYCLOAK_ADMIN_USERNAME');
  const password = readEnvValue('KEYCLOAK_ADMIN_PASSWORD');
  kcadm([
    'config', 'credentials',
    '--server', 'http://localhost:8080/auth',
    '--realm', 'master',
    '--user', username,
    '--password', password,
  ]);
}
