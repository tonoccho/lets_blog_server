/**
 * @jest-environment node
 */

/**
 * issue #1364(親issue #1261 分割C): `/users` の登録日セルをクライアント部品
 * `ViewerDateTime` へ切り出した。ページが `getViewerTimeZone()` の値をそのまま
 * `personalTimeZone` として、各行の `user.createdAt` を `iso` としてそのまま渡すことを、
 * `admin/ssh-keys/__tests__/page.test.tsx`(issue #1362)と同じ手法(対象ページを
 * 非同期関数として直接呼び、依存モジュールを丸ごとモックする)で検査する。
 *
 * `SshKeyPairsPanel`は直下の子として1個だけ現れるため`admin/ssh-keys/__tests__/page.test.tsx`
 * は直下探索で足りるが、`/users`の各行は`users.map()`が作る配列としてtbodyの奥に現れるため、
 * このファイルでは再帰的に`ViewerDateTime`要素を集める。
 *
 * ページを直接呼ぶだけ(実際にはレンダリングしない)なので、`ViewerDateTime`本体を
 * importしたりモックしたりする必要は無い。返るJSXツリー上の要素は`React.createElement`が
 * 作るプレーンなオブジェクトであり、`type`関数の`.name`で狙った要素を判定できる。
 * これにより、テストファースト(RED)の時点で`@/components/ViewerDateTime`が
 * まだ存在しなくても、このテストファイル自体はモジュール解決エラーにならず、
 * 「要素が見つからない」という意味のある失敗のまま書ける。
 */

const requireAdminSession = jest.fn();
const getViewerTimeZone = jest.fn();
const getViewerProfile = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...a: unknown[]) => requireAdminSession(...a),
  getViewerTimeZone: (...a: unknown[]) => getViewerTimeZone(...a),
  getViewerProfile: (...a: unknown[]) => getViewerProfile(...a),
}));

const listUsers = jest.fn();
const listProjects = jest.fn();
const listAllProjectUsers = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  listUsers: (...a: unknown[]) => listUsers(...a),
  listProjects: (...a: unknown[]) => listProjects(...a),
  listAllProjectUsers: (...a: unknown[]) => listAllProjectUsers(...a),
}));

jest.mock('../UserForm', () => ({ UserForm: () => null }));
jest.mock('../DeleteUserButton', () => ({ DeleteUserButton: () => null }));

import UsersPage from '../page';

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

describe('/users の登録日セルとTZ受け渡し(issue #1364)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
    listProjects.mockResolvedValue([]);
    listAllProjectUsers.mockResolvedValue([]);
    getViewerProfile.mockResolvedValue(null);
  });

  it('一覧取得に成功したとき、各ユーザーのcreatedAtと個人設定TZをそのままViewerDateTimeへ渡す', async () => {
    const users = [
      { id: 1, email: 'a@example.com', role: 'user', createdAt: '2026-09-08T20:03:35' },
      { id: 2, email: 'b@example.com', role: 'admin', createdAt: '2026-09-09T01:00:00' },
    ];
    listUsers.mockResolvedValue(users);
    getViewerTimeZone.mockResolvedValue('Asia/Tokyo');

    const result = (await UsersPage()) as unknown as ReactElementLike;

    const elements = collectElementsByTypeName(result, 'ViewerDateTime');
    expect(elements).toHaveLength(2);
    expect(elements[0].props.iso).toBe(users[0].createdAt);
    expect(elements[0].props.personalTimeZone).toBe('Asia/Tokyo');
    expect(elements[1].props.iso).toBe(users[1].createdAt);
    expect(elements[1].props.personalTimeZone).toBe('Asia/Tokyo');
  });

  it('一覧取得に失敗したとき、catch(() => [])で空配列にフォールバックしViewerDateTimeを描画しない', async () => {
    listUsers.mockRejectedValue(new Error('一覧取得に失敗しました(テスト用)'));
    getViewerTimeZone.mockResolvedValue(null);

    const result = (await UsersPage()) as unknown as ReactElementLike;

    expect(collectElementsByTypeName(result, 'ViewerDateTime')).toHaveLength(0);
  });
});
