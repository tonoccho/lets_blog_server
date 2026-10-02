/**
 * @jest-environment node
 */

/**
 * issue #1362: `admin/ssh-keys/page.tsx` の `listSshKeyPairs().catch(() => [])` は
 * `scripts/check-changed-coverage.py` でC1/C2 0/1(未検査)だった。
 * `apps/web/src/app/__tests__/dashboardPageSessionGuard.test.tsx` と同じ手法
 * (対象ページを非同期関数として直接呼び、依存モジュールを丸ごとモックする)で、
 * 一覧取得の成功/失敗(catchフォールバック)の両分岐と、`getViewerTimeZone()`の値が
 * `SshKeyPairsPanel`へそのまま渡ることを検査する。
 *
 * `SshKeyPairsPanel`は実際にはレンダリングしない(dashboardPageSessionGuard.test.tsxが
 * パネルを`() => null`にモックして描画しないのと同じ)。ページを直接呼んだ戻り値は
 * `React.createElement`が作るプレーンなオブジェクト(JSX)なので、実際に描画せずとも
 * `.props`を読めば渡された値をそのまま検査できる。
 */

const requireAdminSession = jest.fn();
const getViewerTimeZone = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...a: unknown[]) => requireAdminSession(...a),
  getViewerTimeZone: (...a: unknown[]) => getViewerTimeZone(...a),
}));

const listSshKeyPairs = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  listSshKeyPairs: (...a: unknown[]) => listSshKeyPairs(...a),
}));

jest.mock('../SshKeyPairsPanel', () => ({
  SshKeyPairsPanel: () => null,
}));

import AdminSshKeysPage from '../page';
import { SshKeyPairsPanel } from '../SshKeyPairsPanel';

interface ReactElementLike {
  type: unknown;
  props: Record<string, unknown>;
}

/** ページが返すJSXツリーから`SshKeyPairsPanel`要素を探す(直下の子として描画される)。 */
function findSshKeyPairsPanelElement(pageResult: ReactElementLike): ReactElementLike {
  const children = pageResult.props.children as ReactElementLike[];
  const panel = children.find((child) => child?.type === SshKeyPairsPanel);
  if (!panel) {
    throw new Error('SshKeyPairsPanel要素が見つかりません');
  }
  return panel;
}

describe('/admin/ssh-keys の一覧取得失敗時のフォールバックとTZ受け渡し(issue #1362)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('一覧取得に成功したとき、その一覧と個人設定TZをそのままパネルへ渡す', async () => {
    const keyPairs = [{ id: 1, name: 'production-deploy' }];
    listSshKeyPairs.mockResolvedValue(keyPairs);
    getViewerTimeZone.mockResolvedValue('Asia/Tokyo');

    const result = (await AdminSshKeysPage()) as unknown as ReactElementLike;

    const panel = findSshKeyPairsPanelElement(result);
    expect(panel.props.keyPairs).toBe(keyPairs);
    expect(panel.props.personalTimeZone).toBe('Asia/Tokyo');
  });

  it('一覧取得に失敗してもページ自体は例外にならず描画できる(失敗の表示は sshKeysPageFetchFailure.test.tsx, issue #1458)', async () => {
    listSshKeyPairs.mockRejectedValue(new Error('一覧取得に失敗しました(テスト用)'));
    getViewerTimeZone.mockResolvedValue(null);
    jest.spyOn(console, 'error').mockImplementation(() => undefined);

    await expect(AdminSshKeysPage()).resolves.toBeDefined();
  });
});
