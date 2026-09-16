/**
 * @jest-environment node
 *
 * issue #1295: 受け入れテストを既定の並列度で走らせると、80箇所以上のステップ定義が
 * それぞれ独立に `fetchAccessToken` を呼び、同じ2つのE2E合成アカウントに対して
 * Keycloakのトークンエンドポイントへ大量かつ高頻度にリクエストする。この高頻度な
 * 同時アクセスが Keycloak のブルートフォース検知(quickLoginCheckMilliSeconds、
 * infra/keycloak/realm-export.json)を誤って作動させ、`user_temporarily_disabled` で
 * アカウントが一時ロックされて `invalid_grant` が断続的に発生する(詳細はIssue本文)。
 *
 * 修正は `fetchAccessToken` にアカウント単位のトークンキャッシュ(有効期限を見て再利用)と、
 * 同時呼び出しの合流(同じアカウントへ向けた同時リクエストを1本のHTTPリクエストに束ねる)を
 * 追加すること。この単体テストはその挙動を検証する。
 *
 * `apps/web/e2e/**` は通常 jest の対象外(jest.config.ts の testMatch と対象除外設定。
 * playwright-bddの生成物混入を避けるため、#994)なので、この1ファイルだけは、対象除外設定と
 * testMatchをCLI引数で明示的に上書きした jest 呼び出しで走らせる(実装報告に実行結果を記録):
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/helpers.test.ts' \
 *     e2e/helpers.test.ts
 *
 * helpers.ts 自体は `apps/web/e2e/**` にあるため、CLAUDE.md → Test-First Implementation の
 * コミット分類上は「テストコード」であり(.claude/hooks/paths.py の TEST_PATTERNS)、
 * C1/C2 カバレッジの数値目標(90%)の対象外(90%目標はプロダクションコードにのみ課される)。
 * 実装報告にその旨を明記する。
 *
 * issue #1295フォローアップ(レビュー指摘、note 7363): 上記のキャッシュ/合流はNodeの
 * モジュール単位の状態であり、Playwrightのワーカー(別プロセス)をまたいでは共有されない。
 * 以下の「共有キャッシュファイル」に関するテストは、ワーカーをまたいだ再利用(=同一プロセス内
 * だけでなく、ファイル経由で他プロセスが書いたトークンを読める)を検証する。真に別プロセスを
 * 束ねた検証は token-cross-process.test.ts で行う(このファイルはあくまで単一プロセス内で
 * 共有ファイルの読み書きロジックを検証する)。
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import {
  fetchAccessToken,
  _resetAccessTokenCacheForTests,
  _sharedTokenCacheFilePathForTests,
} from './helpers';

const EMAIL_A = 'e2e-test@letsblog.local';
const EMAIL_B = 'e2e-admin@letsblog.local';
const PASSWORD = 'dummy-password';

/** `request.post(...).json()` が返す最小限のKeycloakトークンレスポンスを組み立てる。 */
function tokenResponseBody(accessToken: string, expiresInSeconds = 300) {
  return { access_token: accessToken, expires_in: expiresInSeconds };
}

/** 呼び出しごとに異なるトークン文字列を返す、遅延応答つきのpostモックを作る。 */
function createDelayedPostMock() {
  let callCount = 0;
  const pending: Array<{ resolve: (value: unknown) => void }> = [];
  const post = jest.fn(() => {
    callCount += 1;
    const token = `token-${callCount}`;
    return new Promise((resolve) => {
      pending.push({
        resolve: () =>
          resolve({
            ok: () => true,
            status: () => 200,
            json: async () => tokenResponseBody(token),
            text: async () => '',
          }),
      });
    });
  });
  return {
    post,
    resolveNext(): void {
      const next = pending.shift();
      if (!next) {
        throw new Error('保留中のリクエストがありません');
      }
      next.resolve(undefined);
    },
    resolveAll(): void {
      while (pending.length > 0) {
        this.resolveNext();
      }
    },
  };
}

/** 即座に解決するpostモックを作る。 */
function createImmediatePostMock(expiresInSeconds = 300) {
  let callCount = 0;
  const post = jest.fn(async () => {
    callCount += 1;
    return {
      ok: () => true,
      status: () => 200,
      json: async () => tokenResponseBody(`token-${callCount}`, expiresInSeconds),
      text: async () => '',
    };
  });
  return post;
}

describe('fetchAccessToken (issue #1295: ブルートフォースロック回避のキャッシュ/合流)', () => {
  let tokenCacheDir: string;

  beforeEach(() => {
    _resetAccessTokenCacheForTests();
    // 共有キャッシュファイルはホストの一時ディレクトリを使うため、テストごとに専用の
    // ディレクトリへ差し替えて他テスト・他プロセスの残留状態から隔離する(#1295フォローアップ)。
    tokenCacheDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lbs-e2e-token-cache-test-'));
    process.env.E2E_TOKEN_CACHE_DIR = tokenCacheDir;
  });

  afterEach(() => {
    delete process.env.E2E_TOKEN_CACHE_DIR;
    fs.rmSync(tokenCacheDir, { recursive: true, force: true });
  });

  test('同じアカウントへの同時呼び出しは1本のHTTPリクエストに合流する', async () => {
    const mock = createDelayedPostMock();
    const request = { post: mock.post } as unknown as APIRequestContext;

    const first = fetchAccessToken(request, EMAIL_A, PASSWORD);
    const second = fetchAccessToken(request, EMAIL_A, PASSWORD);
    mock.resolveAll();

    const [tokenA, tokenB] = await Promise.all([first, second]);

    expect(mock.post).toHaveBeenCalledTimes(1);
    expect(tokenA).toBe(tokenB);
  });

  test('有効期限内はキャッシュしたトークンを再利用し、Keycloakへ再リクエストしない', async () => {
    const post = createImmediatePostMock(300);
    const request = { post } as unknown as APIRequestContext;

    const token1 = await fetchAccessToken(request, EMAIL_A, PASSWORD);
    const token2 = await fetchAccessToken(request, EMAIL_A, PASSWORD);

    expect(post).toHaveBeenCalledTimes(1);
    expect(token1).toBe(token2);
  });

  test('キャッシュの有効期限が切れたら新しいトークンを取得し直す', async () => {
    const post = createImmediatePostMock(1); // 1秒で失効させる(安全マージンを差し引いても即失効)
    const request = { post } as unknown as APIRequestContext;

    const realNow = Date.now;
    let now = realNow();
    jest.spyOn(Date, 'now').mockImplementation(() => now);
    try {
      const token1 = await fetchAccessToken(request, EMAIL_A, PASSWORD);
      now += 60_000; // 有効期限(安全マージン込み)を確実に超えて進める
      const token2 = await fetchAccessToken(request, EMAIL_A, PASSWORD);

      expect(post).toHaveBeenCalledTimes(2);
      expect(token1).not.toBe(token2);
    } finally {
      (Date.now as jest.Mock).mockRestore();
    }
  });

  test('異なるアカウントは互いに独立したキャッシュ/合流を持つ', async () => {
    const mock = createDelayedPostMock();
    const request = { post: mock.post } as unknown as APIRequestContext;

    const forA = fetchAccessToken(request, EMAIL_A, PASSWORD);
    const forB = fetchAccessToken(request, EMAIL_B, PASSWORD);
    mock.resolveAll();
    const [tokenA, tokenB] = await Promise.all([forA, forB]);

    expect(mock.post).toHaveBeenCalledTimes(2);
    expect(tokenA).not.toBe(tokenB);
  });

  test('Keycloakが失敗応答を返した場合はエラーを投げ、キャッシュも作らない', async () => {
    const post = jest.fn(async () => ({
      ok: () => false,
      status: () => 401,
      json: async () => ({}),
      text: async () => 'invalid_grant',
    }));
    const request = { post } as unknown as APIRequestContext;

    await expect(fetchAccessToken(request, EMAIL_A, PASSWORD)).rejects.toThrow(
      /Keycloakからのトークン取得に失敗しました/
    );

    // 失敗はキャッシュされない。次の呼び出しは新たにリクエストを送る。
    await expect(fetchAccessToken(request, EMAIL_A, PASSWORD)).rejects.toThrow();
    expect(post).toHaveBeenCalledTimes(2);
  });

  test('access_tokenを含まない応答はエラーを投げる', async () => {
    const post = jest.fn(async () => ({
      ok: () => true,
      status: () => 200,
      json: async () => ({}),
      text: async () => '',
    }));
    const request = { post } as unknown as APIRequestContext;

    await expect(fetchAccessToken(request, EMAIL_A, PASSWORD)).rejects.toThrow(
      /access_tokenが含まれていません/
    );
  });
});

describe('fetchAccessTokenの共有キャッシュファイル(issue #1295フォローアップ: ワーカーをまたいだ再利用)', () => {
  let tokenCacheDir: string;

  beforeEach(() => {
    _resetAccessTokenCacheForTests();
    tokenCacheDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lbs-e2e-token-cache-test-'));
    process.env.E2E_TOKEN_CACHE_DIR = tokenCacheDir;
  });

  afterEach(() => {
    delete process.env.E2E_TOKEN_CACHE_DIR;
    fs.rmSync(tokenCacheDir, { recursive: true, force: true });
  });

  test('他プロセスが書いた有効な共有キャッシュファイルがあれば、それを使いKeycloakへリクエストしない', async () => {
    const post = jest.fn();
    const request = { post } as unknown as APIRequestContext;

    // このプロセス自身は一度もfetchAccessTokenを呼んでいない(module内メモリキャッシュは空)。
    // 別プロセスが書いた、という体で共有ファイルへ直接書き込む。
    fs.writeFileSync(
      _sharedTokenCacheFilePathForTests(EMAIL_A),
      JSON.stringify({ token: 'token-from-other-worker', expiresAt: Date.now() + 60_000 })
    );

    const token = await fetchAccessToken(request, EMAIL_A, PASSWORD);

    expect(token).toBe('token-from-other-worker');
    expect(post).not.toHaveBeenCalled();
  });

  test('実際にKeycloakから取得したトークンは共有キャッシュファイルへ書き込まれる(他プロセスが再利用できるように)', async () => {
    const post = createImmediatePostMock(300);
    const request = { post } as unknown as APIRequestContext;

    const token = await fetchAccessToken(request, EMAIL_A, PASSWORD);

    const written = JSON.parse(
      fs.readFileSync(_sharedTokenCacheFilePathForTests(EMAIL_A), 'utf8')
    ) as { token: string; expiresAt: number };
    expect(written.token).toBe(token);
    expect(written.expiresAt).toBeGreaterThan(Date.now());
  });

  test('共有キャッシュファイルのトークンが失効していれば無視し、新規に取得し直す', async () => {
    const post = createImmediatePostMock(300);
    const request = { post } as unknown as APIRequestContext;

    fs.writeFileSync(
      _sharedTokenCacheFilePathForTests(EMAIL_A),
      JSON.stringify({ token: 'expired-token', expiresAt: Date.now() - 1_000 })
    );

    const token = await fetchAccessToken(request, EMAIL_A, PASSWORD);

    expect(post).toHaveBeenCalledTimes(1);
    expect(token).not.toBe('expired-token');
  });
});
