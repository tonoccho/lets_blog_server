/**
 * @jest-environment node
 */

/**
 * issue #1234: `/posts` がセッション更新不能時にログイン画面へリダイレクトすることを固定する。
 * 手法は `dashboardPageSessionGuard.test.tsx` と同じ。
 *
 * 自前の @/lib/session・@/lib/apiClient・PostsTable・ViewerDateTime は差し替えない。差し替えるのは
 * Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock("next/navigation", () => ({ redirect: (p: string) => redirect(p) }));

import { installFetch, jsonResponse, resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import PostsPage from "../page";

/** project-service(投稿履歴)と identity-service の代役(gateway の向こう側)。 */
const listPosts = jest.fn();
const getMyProfile = jest.fn();

function setup(session: unknown) {
  listPosts.mockReset();
  getMyProfile.mockReset();
  redirect.mockClear();
  resetServerRuntime(session);
  installFetch({
    "GET /api/posts": async () => jsonResponse(await listPosts()),
    "GET /api/identity/me": async () => jsonResponse(await getMyProfile()),
  });
  listPosts.mockResolvedValue([]);
  getMyProfile.mockResolvedValue(null);
}

/**
 * issue #1364(親issue #1261 分割C): `/posts` の最終投稿日時セルをクライアント部品
 * `ViewerDateTime` へ切り出した。`lastPublishedAt`が無い投稿は従来通り「-」を表示し、
 * `ViewerDateTime`を描画しないことも合わせて検査する。
 *
 * ページを直接呼んで返るJSXツリー上の要素は`React.createElement`が作るプレーンなオブジェクトであり、
 * `type`関数の`.name`で狙った要素を判定できる。
 */
interface ReactElementLike {
  type: unknown;
  props: Record<string, unknown>;
}

/** ページが返すJSXツリーから、`type`関数の名前が`name`と一致する要素を再帰的にすべて集める。 */
function collectElementsByTypeName(node: unknown, name: string, results: ReactElementLike[] = []): ReactElementLike[] {
  if (node == null || typeof node !== 'object') {
    return results;
  }
  if (Array.isArray(node)) {
    for (const child of node) {
      collectElementsByTypeName(child, name, results);
    }
    return results;
  }
  const element = node as ReactElementLike;
  if (typeof element.type === 'function' && (element.type as { name: string }).name === name) {
    results.push(element);
  }
  if (element.props && 'children' in element.props) {
    collectElementsByTypeName(element.props.children, name, results);
  }
  return results;
}

describe('/posts のセッション判定(issue #1234)', () => {
  it('session.error が RefreshAccessTokenError のとき /login へ送り、投稿を取得しない(「全0件を表示」を出さない)', async () => {
    setup({ user: { role: 'user' }, error: 'RefreshAccessTokenError' });

    await expect(PostsPage()).rejects.toThrow('NEXT_REDIRECT:/login');

    expect(listPosts).not.toHaveBeenCalled();
  });

  it('未ログインのときも /login へ送る', async () => {
    setup(null);

    await expect(PostsPage()).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(listPosts).not.toHaveBeenCalled();
  });

  it('セッションが有効なときはリダイレクトせず、実データを取得する(退行なし)', async () => {
    setup({ user: { role: 'user' } });
    listPosts.mockResolvedValue([{ id: 1 }, { id: 2 }]);
    getMyProfile.mockResolvedValue({ id: 1, timezone: 'Asia/Tokyo' });

    await expect(PostsPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
    expect(listPosts).toHaveBeenCalled();
  });

  it('セッションが有効で投稿が0件のときもリダイレクトしない(空状態表示。退行なし)', async () => {
    setup({ user: { role: 'user' } });
    listPosts.mockResolvedValue([]);

    await expect(PostsPage()).resolves.toBeTruthy();

    expect(redirect).not.toHaveBeenCalled();
  });
});

describe('/posts の最終投稿日時セルとTZ受け渡し(issue #1364)', () => {
  beforeEach(() => setup({ user: { role: 'user' } }));

  it('lastPublishedAtがある投稿は、そのisoと個人設定TZをそのままViewerDateTimeへ渡す', async () => {
    const lastPublishedAt = '2026-09-08T20:03:35';
    listPosts.mockResolvedValue([
      {
        id: 1,
        siteName: 'site-a',
        wpPostId: '10',
        slug: 'hello-world',
        status: 'publish',
        categories: [],
        lastPublishedAt,
      },
    ]);
    getMyProfile.mockResolvedValue({ id: 1, timezone: 'Asia/Tokyo' });

    const result = (await PostsPage()) as unknown as ReactElementLike;

    const elements = collectElementsByTypeName(result, 'ViewerDateTime');
    expect(elements).toHaveLength(1);
    expect(elements[0].props.iso).toBe(lastPublishedAt);
    expect(elements[0].props.personalTimeZone).toBe('Asia/Tokyo');
  });

  it('lastPublishedAtが無い投稿はViewerDateTimeを描画しない(「-」表示のまま、退行なし)', async () => {
    listPosts.mockResolvedValue([
      {
        id: 2,
        siteName: 'site-a',
        wpPostId: '11',
        slug: 'draft-post',
        status: 'draft',
        categories: [],
        lastPublishedAt: null,
      },
    ]);
    getMyProfile.mockResolvedValue(null);

    const result = (await PostsPage()) as unknown as ReactElementLike;

    expect(collectElementsByTypeName(result, 'ViewerDateTime')).toHaveLength(0);
  });
});
