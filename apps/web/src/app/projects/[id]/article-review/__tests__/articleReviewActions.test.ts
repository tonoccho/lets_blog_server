/**
 * @jest-environment node
 */

/**
 * issue #1345: 記事レビュー画面の「レビュー」ボタンの Server Action。
 * admin 限定で、成功なら確認先 URL を、失敗ならサーバが返した理由を値として返す。
 */
jest.mock('server-only', () => ({}));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const startArticleReview = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  startArticleReview: (...args: unknown[]) => startArticleReview(...args),
}));

import { startArticleReviewAction } from '../actions';

describe('startArticleReviewAction(issue #1345)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('管理者を確認してからレビューAPIを呼び、テスト環境の投稿URLを返す', async () => {
    startArticleReview.mockResolvedValue({ prNumber: 201, state: 'IN_REVIEW', testPostUrl: 'https://t.example/a/' });

    await expect(startArticleReviewAction(7, 201)).resolves.toEqual({ testPostUrl: 'https://t.example/a/' });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(startArticleReview).toHaveBeenCalledWith(7, 201);
  });

  it('API失敗はサーバが返した理由を error として返し、URLは返さない', async () => {
    startArticleReview.mockRejectedValue(new Error('APIエラー (404): 提出されていません'));

    await expect(startArticleReviewAction(7, 201)).resolves.toEqual({ error: 'APIエラー (404): 提出されていません' });
  });

  it('Error 以外が投げられても文字列化して error に入れる', async () => {
    startArticleReview.mockRejectedValue('boom');

    await expect(startArticleReviewAction(7, 201)).resolves.toEqual({ error: 'boom' });
  });

  it('管理者でないときはレビューAPIへ到達しない', async () => {
    requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT'));

    await expect(startArticleReviewAction(7, 201)).rejects.toThrow('NEXT_REDIRECT');
    expect(startArticleReview).not.toHaveBeenCalled();
  });
});
