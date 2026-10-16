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

// 自前の @/lib/apiClient・@/lib/session・SshKeyPairsPanelは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => ({
  ...jest.requireActual('next/server'),
  ...require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule,
}));
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';

/** ssh-key-service と identity-service の代役(gateway の向こう側)。 */
const listSshKeyPairs = jest.fn();
const getMyProfile = jest.fn();

function installApi() {
  installFetch({
    'GET /api/ssh-key-pairs': async () => {
      try {
        return jsonResponse(await listSshKeyPairs());
      } catch (err) {
        return textResponse(err instanceof Error ? err.message : String(err), 503);
      }
    },
    'GET /api/identity/me': async () => jsonResponse(await getMyProfile()),
  });
}

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
    listSshKeyPairs.mockReset();
    getMyProfile.mockReset();
    resetServerRuntime(ADMIN_SESSION);
    installApi();
  });

  it('一覧取得に成功したとき、その一覧と個人設定TZをそのままパネルへ渡す', async () => {
    const keyPairs = [{ id: 1, name: 'production-deploy' }];
    listSshKeyPairs.mockResolvedValue(keyPairs);
    getMyProfile.mockResolvedValue({ id: 1, timezone: 'Asia/Tokyo' });

    const result = (await AdminSshKeysPage()) as unknown as ReactElementLike;

    const panel = findSshKeyPairsPanelElement(result);
    expect(panel.props.keyPairs).toEqual(keyPairs);
    expect(panel.props.personalTimeZone).toBe('Asia/Tokyo');
  });

  it('一覧取得に失敗してもページ自体は例外にならず描画できる(失敗の表示は sshKeysPageFetchFailure.test.tsx, issue #1458)', async () => {
    listSshKeyPairs.mockRejectedValue(new Error('一覧取得に失敗しました(テスト用)'));
    getMyProfile.mockResolvedValue({ id: 1, timezone: null });
    jest.spyOn(console, 'error').mockImplementation(() => undefined);

    await expect(AdminSshKeysPage()).resolves.toBeDefined();
  });
});
