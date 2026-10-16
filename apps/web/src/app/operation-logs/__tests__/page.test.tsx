/**
 * @jest-environment node
 */

/**
 * issue #1138: `/operation-logs` の絞り込みフォームに開始日時・終了日時を足した。
 * ページは閲覧者TZの壁時計(datetime-local)を UTC の ISO 日時へ換算して API へ渡し、
 * ページ送りのリンクにも範囲を引き継ぐ。ページを直接呼び、返るJSXツリーを検査する
 * (`projects/__tests__/page.test.tsx` と同じ手法)。
 */
// 自前の @/lib/apiClient・@/lib/session・子コンポーネントは差し替えない(ページが返すツリーは描画しない)。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1833)。
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { jsonResponse, resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION, USER_SESSION, installFetchByPath, queryOf } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { OperationLogTimeZoneLabel } from '../OperationLogTimeZoneLabel';
import { BrowserTimeZoneField } from '../BrowserTimeZoneField';

/** operation-log-service と identity-service の代役(gateway の向こう側)。引数は検索文字列。 */
const listUnifiedOperationLogs = jest.fn();
const getMyProfile = jest.fn();

/** 閲覧者の個人設定TZ(identity-service が返すプロフィールの timezone)。 */
function setViewerTimeZone(timezone: string | null) {
  getMyProfile.mockResolvedValue({ id: 1, timezone });
}

function useSession(role: 'admin' | 'user') {
  resetServerRuntime(role === 'admin' ? ADMIN_SESSION : USER_SESSION);
}

import OperationLogsPage from '../page';

interface ElementLike {
  type: unknown;
  props: Record<string, unknown>;
}

function collect(node: unknown, predicate: (e: ElementLike) => boolean, out: ElementLike[] = []): ElementLike[] {
  if (node == null || typeof node !== 'object') return out;
  if (Array.isArray(node)) {
    node.forEach((child) => collect(child, predicate, out));
    return out;
  }
  const element = node as ElementLike;
  if (predicate(element)) out.push(element);
  if (element.props && 'children' in element.props) collect(element.props.children, predicate, out);
  return out;
}

const inputNamed = (root: unknown, name: string) =>
  collect(root, (e) => e.type === 'input' && e.props.name === name)[0];

function installApi() {
  installFetchByPath({
    'GET /api/operation-logs/unified': async (url) => jsonResponse(await listUnifiedOperationLogs(queryOf(url))),
    'GET /api/identity/me': async () => jsonResponse(await getMyProfile()),
  });
}

const emptyPage = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 };

async function render(searchParams: Record<string, string>) {
  return (await OperationLogsPage({ searchParams: Promise.resolve(searchParams) })) as unknown as ElementLike;
}

describe('/operation-logs の日時範囲(issue #1138)', () => {
  beforeEach(() => {
    listUnifiedOperationLogs.mockReset();
    getMyProfile.mockReset();
    useSession('admin');
    setViewerTimeZone('Asia/Tokyo');
    listUnifiedOperationLogs.mockResolvedValue(emptyPage);
    installApi();
  });

  it('フォームに開始日時・終了日時の datetime-local 入力がある', async () => {
    const tree = await render({});

    expect(inputNamed(tree, 'startDate')?.props.type).toBe('datetime-local');
    expect(inputNamed(tree, 'endDate')?.props.type).toBe('datetime-local');
  });

  it('閲覧者TZの入力をUTCのISO日時へ換算してAPIへ渡す(終了はその分の最後の秒まで)', async () => {
    await render({ startDate: '2026-09-10T09:30', endDate: '2026-09-11T09:30' });

    expect(listUnifiedOperationLogs).toHaveBeenCalledWith(
      expect.objectContaining({ startDate: '2026-09-10T00:30:00', endDate: '2026-09-11T00:30:59' })
    );
  });

  it('入力値をフォームへ戻して表示する', async () => {
    const tree = await render({ startDate: '2026-09-10T09:30', endDate: '2026-09-11T09:30' });

    expect(inputNamed(tree, 'startDate')?.props.defaultValue).toBe('2026-09-10T09:30');
    expect(inputNamed(tree, 'endDate')?.props.defaultValue).toBe('2026-09-11T09:30');
  });

  it('未指定ならAPIへ日時を渡さず、フォームは空(従来どおり)', async () => {
    const tree = await render({});

    const args = listUnifiedOperationLogs.mock.calls[0][0];
    expect(args.startDate).toBeUndefined();
    expect(args.endDate).toBeUndefined();
    expect(inputNamed(tree, 'startDate')?.props.defaultValue).toBe('');
    expect(inputNamed(tree, 'endDate')?.props.defaultValue).toBe('');
  });

  it('ページ送りのリンクへ日時の範囲を引き継ぐ', async () => {
    listUnifiedOperationLogs.mockResolvedValue({ ...emptyPage, totalElements: 120, totalPages: 3 });

    const tree = await render({ startDate: '2026-09-10T09:30', endDate: '2026-09-11T09:30', type: 'OPERATION' });

    // 「遅い操作」への導線(issue #1471)もhrefを持つので、ページ送りだけに絞る。
    const links = collect(tree, (e) => typeof e.props?.href === 'string' && String(e.props.href).includes('page='));
    expect(links).toHaveLength(3);
    const query = new URL(links[1].props.href as string, 'http://localhost').searchParams;
    expect(query.get('startDate')).toBe('2026-09-10T09:30');
    expect(query.get('endDate')).toBe('2026-09-11T09:30');
    expect(query.get('type')).toBe('OPERATION');
    expect(query.get('page')).toBe('1');
  });

  it('ページ送りのリンクへキーワードを引き継ぐ', async () => {
    listUnifiedOperationLogs.mockResolvedValue({ ...emptyPage, totalElements: 120, totalPages: 3 });

    const tree = await render({ q: '/api/sites' });

    expect(listUnifiedOperationLogs).toHaveBeenCalledWith(expect.objectContaining({ q: '/api/sites' }));
    const links = collect(tree, (e) => typeof e.props?.href === 'string' && String(e.props.href).includes('page='));
    const query = new URL(links[1].props.href as string, 'http://localhost').searchParams;
    expect(query.get('q')).toBe('/api/sites');
    expect(query.get('page')).toBe('1');
  });

  it('閲覧者TZが未設定でも例外を投げない', async () => {
    setViewerTimeZone(null);

    await expect(render({ startDate: '2026-09-10T09:30' })).resolves.toBeDefined();
    expect(listUnifiedOperationLogs.mock.calls[0][0].startDate).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
  });

  it('APIが失敗したら空一覧にフォールバックする', async () => {
    listUnifiedOperationLogs.mockRejectedValue(new Error('down'));

    await expect(render({})).resolves.toBeDefined();
  });

  it('非adminでも描画できる(監査を選択肢に出さない)', async () => {
    useSession('user');

    const tree = await render({});
    const options = collect(tree, (e) => e.type === 'option').map((e) => e.props.value);
    expect(options).not.toContain('AUDIT');
  });

  it('種別の選択肢にAIと非AIのジョブを別々に出す(issue #1481)', async () => {
    const tree = await render({});
    const options = collect(tree, (e) => e.type === 'option').map((e) => e.props.value);
    expect(options).toEqual(expect.arrayContaining(['AI_JOB', 'SYSTEM_JOB']));
  });

  it('adminには「遅い操作」画面への導線を出す(issue #1471)', async () => {
    const tree = await render({});

    const link = collect(tree, (e) => e.props?.href === '/operation-logs/slow')[0];
    expect(link).toBeDefined();
  });

  it('adminでなければ「遅い操作」画面への導線を出さない(issue #1471)', async () => {
    useSession('user');

    const tree = await render({});

    expect(collect(tree, (e) => e.props?.href === '/operation-logs/slow')).toHaveLength(0);
  });

  it('表示中のタイムゾーンの表記に個人設定TZを渡す(issue #1260)', async () => {
    const tree = await render({});

    const label = collect(tree, (e) => e.type === OperationLogTimeZoneLabel)[0];
    expect(label?.props.personalTimeZone).toBe('Asia/Tokyo');
  });

  it('個人設定TZが未設定ならnullを渡す(ブラウザTZはクライアントで解決する)', async () => {
    setViewerTimeZone(null);

    const tree = await render({});

    const label = collect(tree, (e) => e.type === OperationLogTimeZoneLabel)[0];
    expect(label?.props.personalTimeZone).toBeNull();
  });
});

describe('/operation-logs の日時範囲は表示と同じTZで解釈する(issue #1437)', () => {
  beforeEach(() => {
    listUnifiedOperationLogs.mockReset();
    getMyProfile.mockReset();
    useSession('admin');
    setViewerTimeZone(null);
    listUnifiedOperationLogs.mockResolvedValue(emptyPage);
    installApi();
  });

  it('個人設定TZが未設定なら、フォームで送られたブラウザTZの壁時計として換算する', async () => {
    await render({ startDate: '2026-09-10T09:30', endDate: '2026-09-11T09:30', tz: 'Pacific/Auckland' });

    // Auckland(9月はNZST+12)。UTCとして解釈されると 09:30 のままになる。
    expect(listUnifiedOperationLogs).toHaveBeenCalledWith(
      expect.objectContaining({ startDate: '2026-09-09T21:30:00', endDate: '2026-09-10T21:30:59' })
    );
  });

  it('個人設定TZがあれば、ブラウザTZより個人設定TZを優先する', async () => {
    setViewerTimeZone('Asia/Tokyo');

    await render({ startDate: '2026-09-10T09:30', tz: 'Pacific/Auckland' });

    expect(listUnifiedOperationLogs.mock.calls[0][0].startDate).toBe('2026-09-10T00:30:00');
  });

  it('不正なTZ名は無視する(例外を投げず、従来どおりの既定TZで換算する)', async () => {
    await expect(render({ startDate: '2026-09-10T09:30', tz: 'Not/AZone' })).resolves.toBeDefined();

    expect(listUnifiedOperationLogs.mock.calls[0][0].startDate).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
  });

  it('フォームにブラウザTZを送る入力を、個人設定TZを渡して置く', async () => {
    setViewerTimeZone('Asia/Tokyo');

    const tree = await render({});

    const field = collect(tree, (e) => e.type === BrowserTimeZoneField)[0];
    expect(field?.props.personalTimeZone).toBe('Asia/Tokyo');
  });

  it('ページ送りのリンクへブラウザTZを引き継ぐ(個人設定TZ未設定のとき)', async () => {
    listUnifiedOperationLogs.mockResolvedValue({ ...emptyPage, totalElements: 120, totalPages: 3 });

    const tree = await render({ startDate: '2026-09-10T09:30', tz: 'Pacific/Auckland' });

    const links = collect(tree, (e) => typeof e.props?.href === 'string');
    const query = new URL(links[1].props.href as string, 'http://localhost').searchParams;
    expect(query.get('tz')).toBe('Pacific/Auckland');
  });

  it('個人設定TZがあるときはページ送りへtzを付けない', async () => {
    setViewerTimeZone('Asia/Tokyo');
    listUnifiedOperationLogs.mockResolvedValue({ ...emptyPage, totalElements: 120, totalPages: 3 });

    const tree = await render({ startDate: '2026-09-10T09:30', tz: 'Pacific/Auckland' });

    const links = collect(tree, (e) => typeof e.props?.href === 'string');
    const query = new URL(links[1].props.href as string, 'http://localhost').searchParams;
    expect(query.has('tz')).toBe(false);
  });
});

