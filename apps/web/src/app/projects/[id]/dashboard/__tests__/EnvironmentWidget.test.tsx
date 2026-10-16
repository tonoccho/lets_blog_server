import { render, screen, within, fireEvent, waitFor } from '@testing-library/react';
import type { Project, Site } from '@/lib/apiClient';
import { EnvironmentWidget } from '../EnvironmentWidget';

/**
 * 自前の Server Action(@/app/sites/actions)・CheckConnectionButton・@/lib/session・@/lib/apiClient は差し替えない。
 * 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { installFetch, jsonResponse, resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime';

/** 疎通確認 API(gateway の向こう)の代役。受け取ったサイトIDを記録する。 */
const checked: number[] = [];
let respond: () => Response = () => jsonResponse({});
const check = {
  calls: checked,
  mockResolvedValue(body: unknown) {
    respond = () => jsonResponse(body);
  },
};

const site = (id: number, siteKey: string, extra: Partial<Site> = {}): Site =>
  ({ id, siteKey, name: `名前-${siteKey}`, baseUrl: `https://${siteKey}.example.com`, adminPath: null, ...extra }) as Site;

const project = (over: Partial<Project> = {}): Project =>
  ({ id: 7, name: '案件', localSite: null, testSite: null, productionSite: null, ...over }) as Project;

const slotOf = (name: string) => screen.getByRole('heading', { name }).parentElement as HTMLElement;

describe('EnvironmentWidget(issue #1671: 表示専用)', () => {
  beforeEach(() => {
    checked.length = 0;
    resetServerRuntime(ADMIN_SESSION);
    respond = () => jsonResponse({});
    installFetch({
      'POST /api/sites/:id/test-connection': (_u, _i, [id]) => {
        checked.push(Number(id));
        return respond();
      },
    });
  });

  it('紐付いた環境にサイトキー・サイト名・サイトを開く/管理画面を開くリンクを出す', () => {
    render(<EnvironmentWidget project={project({ testSite: site(1, 'test-key') })} />);

    const slot = within(slotOf('テスト環境'));
    expect(slot.getByText('test-key')).toBeInTheDocument();
    expect(slot.getByText('名前-test-key')).toBeInTheDocument();
    expect(slot.getByRole('link', { name: '名前-test-key のサイトを開く' })).toHaveAttribute(
      'href',
      'https://test-key.example.com',
    );
    expect(slot.getByRole('link', { name: '名前-test-key の管理画面を開く' })).toBeInTheDocument();
  });

  it('未紐付けの環境は「未設定」だけで、疎通確認ボタンも出さない', () => {
    render(<EnvironmentWidget project={project()} />);

    for (const name of ['ローカル環境', 'テスト環境', '本番環境']) {
      const slot = within(slotOf(name));
      expect(slot.getByText('未設定')).toBeInTheDocument();
      expect(slot.queryByRole('button')).not.toBeInTheDocument();
    }
  });

  it('紐付けのセレクトも紐付け・切離しのボタンも出さない', () => {
    render(
      <EnvironmentWidget project={project({ testSite: site(1, 'test-key'), productionSite: site(2, 'prod-key') })} />,
    );

    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /紐付/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /切離し/ })).not.toBeInTheDocument();
  });

  it('紐付いた環境ごとに「疎通確認」ボタンを1つずつ置く', () => {
    render(
      <EnvironmentWidget project={project({ testSite: site(1, 'test-key'), productionSite: site(2, 'prod-key') })} />,
    );

    expect(screen.getAllByRole('button', { name: '疎通確認' })).toHaveLength(2);
  });

  it('ボタンを押すまで疎通確認は行われず、結果も出ない', () => {
    render(<EnvironmentWidget project={project({ testSite: site(1, 'test-key') })} />);

    expect(checked).toEqual([]);
    expect(screen.queryByText('SUCCESS')).not.toBeInTheDocument();
    expect(screen.queryByText('FAILED')).not.toBeInTheDocument();
  });

  it('押した環境のサイトIDで確認し、結果をその環境の欄にだけ表示する', async () => {
    check.mockResolvedValue({
      connectionCheckStatus: 'FAILED',
      hasAdminCapability: null,
      failureReason: '資格情報が正しくありません',
      detail: null,
    });
    render(
      <EnvironmentWidget project={project({ testSite: site(1, 'test-key'), productionSite: site(2, 'prod-key') })} />,
    );

    fireEvent.click(within(slotOf('本番環境')).getByRole('button', { name: '疎通確認' }));

    await waitFor(() => expect(within(slotOf('本番環境')).getByText('FAILED')).toBeInTheDocument());
    expect(checked).toEqual([2]);
    expect(within(slotOf('本番環境')).getByText('資格情報が正しくありません')).toBeInTheDocument();
    expect(within(slotOf('テスト環境')).queryByText('FAILED')).not.toBeInTheDocument();
  });

  it('成功時はSUCCESSを表示する', async () => {
    check.mockResolvedValue({ connectionCheckStatus: 'SUCCESS', hasAdminCapability: true, failureReason: null, detail: 'ok' });
    render(<EnvironmentWidget project={project({ localSite: site(3, 'local-key') })} />);

    fireEvent.click(screen.getByRole('button', { name: '疎通確認' }));

    await waitFor(() => expect(within(slotOf('ローカル環境')).getByText('SUCCESS')).toBeInTheDocument());
  });

  it('管理者権限が無いサイトでは警告を表示する', async () => {
    check.mockResolvedValue({ connectionCheckStatus: 'SUCCESS', hasAdminCapability: false, failureReason: null, detail: null });
    render(<EnvironmentWidget project={project({ testSite: site(1, 'test-key') })} />);

    fireEvent.click(screen.getByRole('button', { name: '疎通確認' }));

    await waitFor(() => expect(screen.getByText(/管理者権限\(ユーザー作成\)がありません/)).toBeInTheDocument());
  });
});
