import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/**
 * issue #1295フォローアップ(QAのFAIL、note 7391): 受け入れテストを既定の並列度で実行すると、
 * ブラウザ対話ログイン(`apps/web/e2e/helpers.ts`の`loginViaKeycloak`、clientId="letsblog-web"、
 * Authorization Code フロー)でも`user_temporarily_disabled`が2件再現した。これは
 * `fetchAccessToken`(パスワードグラント、clientId="letsblog-e2e")とは全く別のコード経路であり、
 * `./token-cache`が実装していたアカウント単位クロスプロセスロック(`withAccountLock`、
 * issue #1295の最初のフォローアップ)は`fetchAccessToken`専用で、ブラウザ経由のログインには
 * 一切効いていなかった。
 *
 * ロック本体をここへ切り出し、`fetchAccessToken`と`loginViaKeycloak`の両方が
 * **同じアカウント単位ロックファイル**(メールアドレスをキーにする)を使うようにする。
 * これにより「同一アカウントに対する実Keycloak認証リクエストは、パスワードグラントか
 * 対話ログインかを問わず同時に1本まで」に揃い、`quickLoginCheckMilliSeconds`(1秒)以内に
 * 同一アカウントへの認証試行が重なる状況そのものが、経路によらず起きなくなる。
 *
 * ロジック自体は`./token-cache`のクロスプロセスロック(`flock(1)`をNode側で保持したfd経由で
 * 呼ぶ、`at-lock.ts`と同じ手法)をそのまま踏襲する。ロックファイルの置き場所も、
 * 意図的に`./token-cache`の共有トークンキャッシュファイルと同じディレクトリ
 * (`E2E_TOKEN_CACHE_DIR`環境変数、既定`XDG_RUNTIME_DIR`優先→`os.tmpdir()`)を使う——
 * 単に慣習を合わせているのではなく、両者が指す先が一致していること自体が「両経路が
 * 同じロックファイルで直列化される」という設計の前提になっている。
 */
const DEFAULT_LOCK_TIMEOUT_MS = 30_000;
const ACCOUNT_LOCK_POLL_MS = 50;

/** ロックファイルを置くディレクトリ。`E2E_TOKEN_CACHE_DIR`で上書きできる(テストでの隔離用)。 */
function lockDir(): string {
  return process.env.E2E_TOKEN_CACHE_DIR || process.env.XDG_RUNTIME_DIR || os.tmpdir();
}

/** メールアドレスをファイル名に安全に埋め込む。 */
function accountFileKey(email: string): string {
  return Buffer.from(email, 'utf8').toString('base64url');
}

/**
 * アカウント単位のロックファイルパス。`./token-cache`の共有トークンキャッシュファイル
 * (`lets-blog-server-e2e-token-<key>.json`)と同じ命名規則で`.lock`を付けたもの——
 * 過去に`token-cache.ts`内で使っていたパスと完全に一致させ、既存の共有キャッシュファイルの
 * 隣にロックファイルが並ぶ、という見た目上の互換性を保つ(実際の排他はファイルの中身ではなく
 * パスそのものが一致していることに依存する)。
 */
function accountLockFilePath(email: string): string {
  return path.join(lockDir(), `lets-blog-server-e2e-token-${accountFileKey(email)}.json.lock`);
}

/**
 * ロックファイルのfdへ、非ブロッキングで`flock(2)`を試みる。獲得できればtrue。
 * `at-lock.ts`の`tryAcquireNonBlocking`と同じ考え方(spawn自体の失敗とロック競合を区別する)。
 */
function tryAcquireAccountLockNonBlocking(fd: number): boolean {
  try {
    execFileSync('flock', ['-n', '3'], { stdio: ['ignore', 'ignore', 'ignore', fd] });
    return true;
  } catch (error) {
    if (error && typeof error === 'object' && (error as NodeJS.ErrnoException).code === 'ENOENT') {
      throw new Error(
        'flock コマンドが見つかりません。' +
          'アカウント単位のKeycloak認証ロックにはflockユーティリティ(util-linux等)が必要です。'
      );
    }
    return false;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 指定アカウントに対するクロスプロセスロックを獲得してから`fn`を実行する。
 * 同一ホスト上の他プロセス(他のPlaywrightワーカー)が同じアカウントのロックを保持している間は、
 * 非ブロッキングでの再試行を繰り返して待つ。
 *
 * `fetchAccessToken`(実HTTPリクエスト1本、既定30秒)より`loginViaKeycloak`
 * (ページ遷移・フォーム操作を伴う対話ログイン)の方が1回あたりの所要時間が長く、
 * 既定の並列度ではロック待ちの列も長くなりうるため、呼び出し側で`timeoutMs`を
 * 上書きできるようにしてある。
 */
export async function withAccountLock<T>(
  email: string,
  fn: () => Promise<T>,
  options?: { timeoutMs?: number }
): Promise<T> {
  const timeoutMs = options?.timeoutMs ?? DEFAULT_LOCK_TIMEOUT_MS;
  const fd = fs.openSync(accountLockFilePath(email), 'a+');
  const started = Date.now();
  try {
    for (;;) {
      if (tryAcquireAccountLockNonBlocking(fd)) {
        break;
      }
      if (Date.now() - started > timeoutMs) {
        throw new Error(`アカウント(${email})の認証ロックを${timeoutMs}ms待っても獲得できませんでした`);
      }
      await sleep(ACCOUNT_LOCK_POLL_MS);
    }
    return await fn();
  } finally {
    // fdをcloseすればflock(2)は自動的に解放される(at-lock.tsと同じ仕組み)。
    fs.closeSync(fd);
  }
}
