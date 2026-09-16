import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { withAccountLock } from './account-lock';

/**
 * issue #1295: `fetchAccessToken`のアカウント単位キャッシュ/同時呼び出しの合流。
 *
 * 80箇所以上のステップ定義がそれぞれ独立にこの関数を呼び、既定の並列度(4ワーカー)で
 * 実行すると、実質2つしかないE2E合成アカウント(letsblog-e2e)に対してKeycloakの
 * トークンエンドポイントへ高頻度でパスワードグラントが飛ぶ。この高頻度・高並列なアクセスが
 * Keycloakのブルートフォース検知(`bruteForceProtected`、
 * infra/keycloak/realm-export.json)の quick login 判定
 * (`quickLoginCheckMilliSeconds` = 1000ms 以内に同一アカウントへの認証試行が重なると
 * `minimumQuickLoginWaitSeconds` = 60秒の一時ロックを課す)を作動させ、
 * `user_temporarily_disabled` によるロックと、それに続く断続的な `invalid_grant` を
 * 引き起こしていた(QA #1130、2026-09-14。`docker logs lbs-keycloak` の
 * `LOGIN_ERROR ... error="user_temporarily_disabled"` で確認済み)。
 *
 * `bruteForceProtected` 自体は本番相当の保護であり弱めない(issue #1056)。
 * 代わりにこちら側のリクエスト量そのものを減らす。
 *
 * ## issue #1295フォローアップ(レビュー指摘、note 7363): ワーカーをまたいだ排他が無かった
 *
 * 最初の実装(モジュール変数の`tokenCache`/`inFlightRequests`によるキャッシュ・合流)は
 * Nodeのプロセス内状態でしかない。Playwrightのワーカーは別OSプロセスであり、各ワーカーの
 * `tokenCache`は起動直後どれも空である。全ワーカーがほぼ同時に起動する「受け入れテスト開始
 * 直後」は、2アカウントに対して最大4ワーカー(既定の並列度)が独立に実リクエストを
 * 発行しうる状態そのものであり、レビューで指摘された通りこれが実際に18/20件の
 * `user_temporarily_disabled`を再現した障害モードである。
 *
 * これを閉じるため、プロセス内キャッシュの下にもう1段、**ホストの一時ディレクトリ上の
 * 共有キャッシュファイル**(アカウント単位、1ファイル)を置く:
 *
 *   1. プロセス内キャッシュ(既存): 同一プロセス内の再利用・同時呼び出しの合流。
 *   2. 共有キャッシュファイル({@link readSharedTokenCache}): 他プロセスが書いた
 *      有効なトークンをそのまま読める。プロセスをまたいでもKeycloakへ実リクエストを
 *      送らずに済む(このファイルが本来の目的)。
 *   3. アカウント単位のクロスプロセスロック({@link withAccountLock}、実体は`./account-lock`。
 *      `flock(1)`を使う、`at-lock.ts`と同じ「fdをNode側で保持し、`flock`コマンドへfdを
 *      継承させてロックを張る」手法): 共有キャッシュも失効/未存在のときだけ、アカウントごとに
 *      1プロセスのみが実HTTPリクエストを送るよう直列化する。ロック獲得後にもう一度キャッシュを
 *      読み直す(二重チェック)ため、待っていた他プロセスは実リクエストを送らず、ロック保持者が
 *      書いた共有キャッシュを読むだけで済む。
 *
 *      issue #1295フォローアップ(QAのFAIL、note 7391): このロックはもともとこのファイルに
 *      直接実装されていたが、ブラウザ対話ログイン(`helpers.ts`の`loginViaKeycloak`)が
 *      全く別のコード経路(clientId="letsblog-web"、Authorization Codeフロー)で
 *      同じ2つのE2Eアカウントへ実Keycloak認証を送っており、この経路にはロックが一切
 *      効いていなかったため、`user_temporarily_disabled`が再現した。ロック本体を
 *      `./account-lock`へ切り出し、`fetchAccessToken`と`loginViaKeycloak`の両方が
 *      同じアカウント単位ロックファイルを使うようにして、経路によらず同一アカウントへの
 *      実Keycloak認証リクエストを同時1本までに揃える(詳細は`./account-lock`参照)。
 *
 * この3段構成により、通常運用では**アカウントごとに実行全体でせいぜい1回**しか
 * Keycloakへ実リクエストが飛ばない(`seed.setup.ts`が受け入れテスト全体の最初に1回だけ
 * {@link prefetchAccessTokensForAllE2eAccounts}経由で先取りするため。詳細はそちらのコメント)。
 * トークンの有効期間(`accessTokenLifespan`=300秒、realm-export.json)を超えて実行が続いた
 * 場合のみ、上記3のロックで直列化された再取得が起きる。
 *
 * ワーカー起動直後の最悪ケースと`failureFactor`(=5)との比較は実装報告に記録する。
 */
const EXPIRY_SAFETY_MARGIN_MS = 10_000;
const DEFAULT_TOKEN_TTL_SECONDS = 60;
const E2E_CLIENT_ID = 'letsblog-e2e';

interface CachedToken {
  token: string;
  expiresAt: number;
}

const tokenCache = new Map<string, CachedToken>();
const inFlightRequests = new Map<string, Promise<string>>();

/** テスト専用: モジュール内のキャッシュ/合流状態をリセットする(#1295)。 */
export function _resetAccessTokenCacheForTests(): void {
  tokenCache.clear();
  inFlightRequests.clear();
}

/**
 * 共有キャッシュファイルを置くディレクトリ。`E2E_TOKEN_CACHE_DIR`で上書きできる
 * (テストでの隔離、および本番同様の一時ディレクトリを使いたくない場合向け)。
 * 既定は`at-lock.ts`と同じ考え方で`XDG_RUNTIME_DIR`優先、無ければ`os.tmpdir()`。
 */
function sharedCacheDir(): string {
  return process.env.E2E_TOKEN_CACHE_DIR || process.env.XDG_RUNTIME_DIR || os.tmpdir();
}

/** メールアドレスをファイル名に安全に埋め込む。 */
function accountFileKey(email: string): string {
  return Buffer.from(email, 'utf8').toString('base64url');
}

function sharedTokenCacheFilePath(email: string): string {
  return path.join(sharedCacheDir(), `lets-blog-server-e2e-token-${accountFileKey(email)}.json`);
}

/** テスト専用: 指定アカウントの共有キャッシュファイルのパスを返す(#1295フォローアップ)。 */
export function _sharedTokenCacheFilePathForTests(email: string): string {
  return sharedTokenCacheFilePath(email);
}

function readSharedTokenCache(email: string): CachedToken | undefined {
  try {
    const text = fs.readFileSync(sharedTokenCacheFilePath(email), 'utf8');
    return JSON.parse(text) as CachedToken;
  } catch {
    return undefined;
  }
}

/** 一時ファイル+rename でアトミックに書く(読み手が書きかけの内容を拾わないように)。 */
function writeSharedTokenCache(email: string, record: CachedToken): void {
  const target = sharedTokenCacheFilePath(email);
  const tmp = `${target}.${process.pid}.${Date.now()}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(record));
  fs.renameSync(tmp, target);
}

export async function fetchAccessToken(
  request: APIRequestContext,
  email: string,
  password: string
): Promise<string> {
  const cached = tokenCache.get(email);
  if (cached && cached.expiresAt > Date.now()) {
    return cached.token;
  }

  const shared = readSharedTokenCache(email);
  if (shared && shared.expiresAt > Date.now()) {
    tokenCache.set(email, shared);
    return shared.token;
  }

  const inFlight = inFlightRequests.get(email);
  if (inFlight) {
    return inFlight;
  }

  const requestPromise = withAccountLock(email, async () => {
    // ロック待ちの間に、別プロセス(このプロセス内の別呼び出しではなく、ロックを
    // 先に獲得していた他ワーカー)が既に取得・共有キャッシュへ書き込んでいるかもしれない
    // ので、実リクエストの前にもう一度読み直す(二重チェックロッキング)。
    const sharedAfterLock = readSharedTokenCache(email);
    if (sharedAfterLock && sharedAfterLock.expiresAt > Date.now()) {
      tokenCache.set(email, sharedAfterLock);
      return sharedAfterLock.token;
    }

    const { token, expiresInSeconds } = await requestNewAccessToken(request, email, password);
    const record: CachedToken = {
      token,
      expiresAt: Date.now() + Math.max(expiresInSeconds * 1000 - EXPIRY_SAFETY_MARGIN_MS, 0),
    };
    tokenCache.set(email, record);
    writeSharedTokenCache(email, record);
    return token;
  }).finally(() => {
    inFlightRequests.delete(email);
  });

  inFlightRequests.set(email, requestPromise);
  return requestPromise;
}

async function requestNewAccessToken(
  request: APIRequestContext,
  email: string,
  password: string
): Promise<{ token: string; expiresInSeconds: number }> {
  // e2e-login-guard:locked — このパスワードグラントは呼び出し元(fetchAccessToken)の
  // withAccountLockの中でしか呼ばれない(issue #1295)。
  const response = await request.post('/auth/realms/letsblog/protocol/openid-connect/token', {
    form: {
      grant_type: 'password',
      client_id: E2E_CLIENT_ID,
      username: email,
      password,
    },
  });
  if (!response.ok()) {
    throw new Error(
      `Keycloakからのトークン取得に失敗しました (status=${response.status()}): ${await response.text()}`
    );
  }
  const body = (await response.json()) as { access_token?: string; expires_in?: number };
  if (!body.access_token) {
    throw new Error('Keycloakのレスポンスにaccess_tokenが含まれていません');
  }
  return { token: body.access_token, expiresInSeconds: body.expires_in ?? DEFAULT_TOKEN_TTL_SECONDS };
}
