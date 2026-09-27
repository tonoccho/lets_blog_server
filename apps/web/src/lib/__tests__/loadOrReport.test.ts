/**
 * @jest-environment node
 */

/**
 * issue #1235: Server Component の API 取得失敗を「0件」に見せないための共通ヘルパー。
 */
const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock('next/navigation', () => ({ redirect: (p: string) => redirect(p) }));

import { loadOrReport, failedLabels } from '../loadOrReport';
import { SESSION_EXPIRED_MESSAGE } from '../sessionExpired';

describe('loadOrReport', () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    jest.clearAllMocks();
    errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => errorSpy.mockRestore());

  it('成功したときは値をそのまま返し、ログを出さない', async () => {
    const result = await loadOrReport('sites', 'サイト一覧', Promise.resolve([1, 2]), [] as number[]);
    expect(result).toEqual({ data: [1, 2], failed: false, label: 'サイト一覧' });
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('失敗したときはフォールバックと failed=true を返し、scope・ラベル・原因をログに残す', async () => {
    const err = new Error('APIエラー (503): down');
    const result = await loadOrReport('sites', 'サイト一覧', Promise.reject(err), [] as number[]);
    expect(result).toEqual({ data: [], failed: true, label: 'サイト一覧' });
    expect(errorSpy).toHaveBeenCalledWith('[sites] サイト一覧の取得に失敗しました:', err);
  });

  it('文字列など Error 以外が投げられても失敗として扱う', async () => {
    const result = await loadOrReport('s', 'x', Promise.reject('boom'), null);
    expect(result.failed).toBe(true);
  });

  it('セッション切れの文言のときは /login へリダイレクトする', async () => {
    await expect(
      loadOrReport('sites', 'サイト一覧', Promise.reject(new Error(SESSION_EXPIRED_MESSAGE)), []),
    ).rejects.toThrow('NEXT_REDIRECT:/login');
  });

  it('404 は notFoundIsEmpty 指定時のみ「失敗ではない空」として扱い、ログも出さない', async () => {
    const notFound = () => Promise.reject(new Error('APIエラー (404): Not Found'));
    const empty = await loadOrReport('s', 'x', notFound(), null, { notFoundIsEmpty: true });
    expect(empty).toEqual({ data: null, failed: false, label: 'x' });
    expect(errorSpy).not.toHaveBeenCalled();

    const failed = await loadOrReport('s', 'x', notFound(), null);
    expect(failed.failed).toBe(true);
  });

  it('notFoundIsEmpty でも 404 以外(500)は失敗として扱う', async () => {
    const result = await loadOrReport('s', 'x', Promise.reject(new Error('APIエラー (500): x')), null, {
      notFoundIsEmpty: true,
    });
    expect(result.failed).toBe(true);
  });
});

describe('failedLabels', () => {
  it('失敗したものだけのラベルを順に返す', () => {
    expect(
      failedLabels(
        { data: 1, failed: false, label: 'a' },
        { data: 1, failed: true, label: 'b' },
        { data: 1, failed: true, label: 'c' },
      ),
    ).toEqual(['b', 'c']);
  });
});
