/**
 * @jest-environment node
 */

/**
 * issue #1364(親issue #1261 分割C): `/projects` の作成日セルをクライアント部品
 * `ViewerDateTime` へ切り出した。ページが `getViewerTimeZone()` の値をそのまま
 * `personalTimeZone` として、各行の `project.createdAt` を `iso` としてそのまま渡すことを、
 * `apps/web/src/app/users/__tests__/page.test.tsx`(issue #1364)と同じ手法で検査する。
 *
 * ページを直接呼ぶだけ(実際にはレンダリングしない)なので、`ViewerDateTime`本体を
 * importしたりモックしたりする必要は無い(理由は`users/__tests__/page.test.tsx`の
 * 先頭コメント参照)。
 */

const requireAdminSession = jest.fn();
const getViewerTimeZone = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...a: unknown[]) => requireAdminSession(...a),
  getViewerTimeZone: (...a: unknown[]) => getViewerTimeZone(...a),
}));

const listProjects = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  listProjects: (...a: unknown[]) => listProjects(...a),
}));

jest.mock('../ProjectForm', () => ({ ProjectForm: () => null }));
jest.mock('../ProjectsTable', () => ({ ProjectsTable: () => null }));

import ProjectsPage from '../page';

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

function project(overrides: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: 1,
    name: 'テストプロジェクト',
    slug: 'test-project',
    localSite: null,
    testSite: null,
    productionSite: null,
    createdAt: '2026-09-08T20:03:35',
    ...overrides,
  };
}

describe('/projects の作成日セルとTZ受け渡し(issue #1364)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('一覧取得に成功したとき、各プロジェクトのcreatedAtと個人設定TZをそのままViewerDateTimeへ渡す', async () => {
    const projects = [project({ id: 1, createdAt: '2026-09-08T20:03:35' })];
    listProjects.mockResolvedValue(projects);
    getViewerTimeZone.mockResolvedValue('Asia/Tokyo');

    const result = (await ProjectsPage()) as unknown as ReactElementLike;

    const elements = collectElementsByTypeName(result, 'ViewerDateTime');
    expect(elements).toHaveLength(1);
    expect(elements[0].props.iso).toBe(projects[0].createdAt);
    expect(elements[0].props.personalTimeZone).toBe('Asia/Tokyo');
  });

  it('一覧取得に失敗したとき、catch(() => [])で空配列にフォールバックしViewerDateTimeを描画しない', async () => {
    listProjects.mockRejectedValue(new Error('一覧取得に失敗しました(テスト用)'));
    getViewerTimeZone.mockResolvedValue(null);

    const result = (await ProjectsPage()) as unknown as ReactElementLike;

    expect(collectElementsByTypeName(result, 'ViewerDateTime')).toHaveLength(0);
  });
});
