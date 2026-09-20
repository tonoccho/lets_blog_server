/**
 * @jest-environment node
 */

/**
 * issue #1366(親issue #1261 分割B-2): `plan/page.tsx` は `getViewerTimeZone()` を
 * 呼んでおらず、`ArticlePlanWorkspace` へタイムゾーンを渡す経路が無かった。
 * `admin/ssh-keys/__tests__/page.test.tsx`(issue #1362)と同じ手法(対象ページを
 * 非同期関数として直接呼び、依存モジュールを丸ごとモックする)で、`getViewerTimeZone()`の
 * 値がそのまま`ArticlePlanWorkspace`へ渡ることを検査する。
 *
 * `ArticlePlanWorkspace`は実際にはレンダリングしない(`() => null`にモック)。ページを
 * 直接呼んだ戻り値は`React.createElement`が作るプレーンなオブジェクト(JSX)なので、
 * `.props`を読めば渡された値をそのまま検査できる。
 */

const requireAdminSession = jest.fn();
const getViewerTimeZone = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...a: unknown[]) => requireAdminSession(...a),
  getViewerTimeZone: (...a: unknown[]) => getViewerTimeZone(...a),
}));

const getProject = jest.fn();
const listArticlePlanSessions = jest.fn();
const listArticlePlanIssues = jest.fn();
const getArticlePlanSessionByIssue = jest.fn();
const getArticlePlanIssueDescription = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  getProject: (...a: unknown[]) => getProject(...a),
  listArticlePlanSessions: (...a: unknown[]) => listArticlePlanSessions(...a),
  listArticlePlanIssues: (...a: unknown[]) => listArticlePlanIssues(...a),
  getArticlePlanSessionByIssue: (...a: unknown[]) => getArticlePlanSessionByIssue(...a),
  getArticlePlanIssueDescription: (...a: unknown[]) => getArticlePlanIssueDescription(...a),
}));

jest.mock('../ArticlePlanWorkspace', () => ({ ArticlePlanWorkspace: () => null }));
jest.mock('../ArticlePlanIssueList', () => ({ ArticlePlanIssueList: () => null }));

import ArticlePlanPage from '../page';
import { ArticlePlanWorkspace } from '../ArticlePlanWorkspace';

interface ReactElementLike {
  type: unknown;
  props: Record<string, unknown>;
}

/** ページが返すJSXツリーから`ArticlePlanWorkspace`要素を探す(直下の子として描画される)。 */
function findWorkspaceElement(pageResult: ReactElementLike): ReactElementLike {
  const children = pageResult.props.children as ReactElementLike[];
  const workspace = children.find((child) => child?.type === ArticlePlanWorkspace);
  if (!workspace) {
    throw new Error('ArticlePlanWorkspace要素が見つかりません');
  }
  return workspace;
}

describe('/projects/[id]/plan のタイムゾーン受け渡し(issue #1366)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
    getProject.mockResolvedValue({ id: 1, name: 'テストプロジェクト', githubRepository: null });
    listArticlePlanSessions.mockResolvedValue([]);
    listArticlePlanIssues.mockResolvedValue([]);
    getArticlePlanSessionByIssue.mockResolvedValue(null);
    getArticlePlanIssueDescription.mockResolvedValue(null);
  });

  it('個人設定TZが設定されているとき、そのままArticlePlanWorkspaceへ渡す', async () => {
    getViewerTimeZone.mockResolvedValue('Asia/Tokyo');

    const result = (await ArticlePlanPage({
      params: Promise.resolve({ id: '1' }),
      searchParams: Promise.resolve({}),
    })) as unknown as ReactElementLike;

    const workspace = findWorkspaceElement(result);
    expect(workspace.props.timezone).toBe('Asia/Tokyo');
  });

  it('個人設定TZが未設定(null)のとき、nullのままArticlePlanWorkspaceへ渡す', async () => {
    getViewerTimeZone.mockResolvedValue(null);

    const result = (await ArticlePlanPage({
      params: Promise.resolve({ id: '1' }),
      searchParams: Promise.resolve({}),
    })) as unknown as ReactElementLike;

    const workspace = findWorkspaceElement(result);
    expect(workspace.props.timezone).toBeNull();
  });
});
