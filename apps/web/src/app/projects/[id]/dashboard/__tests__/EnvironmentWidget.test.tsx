import { render, screen, within, fireEvent, waitFor } from '@testing-library/react';
import type { Project, Site } from '@/lib/apiClient';
import { checkSiteConnectionAction } from '@/app/sites/actions';
import { EnvironmentWidget } from '../EnvironmentWidget';

jest.mock('@/app/sites/actions', () => ({ checkSiteConnectionAction: jest.fn() }));

const check = checkSiteConnectionAction as jest.Mock;

const site = (id: number, siteKey: string, extra: Partial<Site> = {}): Site =>
  ({ id, siteKey, name: `名前-${siteKey}`, baseUrl: `https://${siteKey}.example.com`, adminPath: null, ...extra }) as Site;

const project = (over: Partial<Project> = {}): Project =>
  ({ id: 7, name: '案件', localSite: null, testSite: null, productionSite: null, ...over }) as Project;

const slotOf = (name: string) => screen.getByRole('heading', { name }).parentElement as HTMLElement;

describe('EnvironmentWidget(issue #1671: 表示専用)', () => {
  beforeEach(() => check.mockReset());

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

  it('ボタンを押すまで checkSiteConnectionAction は呼ばれず、結果も出ない', () => {
    render(<EnvironmentWidget project={project({ testSite: site(1, 'test-key') })} />);

    expect(check).not.toHaveBeenCalled();
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
    expect(check).toHaveBeenCalledTimes(1);
    expect(check).toHaveBeenCalledWith(2);
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
