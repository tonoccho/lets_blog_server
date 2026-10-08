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
const approveArticleReview = jest.fn();
const rejectArticleReview = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  startArticleReview: (...args: unknown[]) => startArticleReview(...args),
  approveArticleReview: (...args: unknown[]) => approveArticleReview(...args),
  rejectArticleReview: (...args: unknown[]) => rejectArticleReview(...args),
}));

import { approveArticleReviewAction, rejectArticleReviewAction, startArticleReviewAction } from '../actions';

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

describe('approveArticleReviewAction(issue #1346)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('管理者を確認してからレビュー完了APIを呼び、本番の投稿URLとブランチ削除の成否を返す', async () => {
    approveArticleReview.mockResolvedValue({
      prNumber: 201, state: 'PUBLISHED', productionPostUrl: 'https://prod.example/a/', wpPostId: '9', merged: true, branchDeleted: true,
    });

    await expect(approveArticleReviewAction(7, 201)).resolves.toEqual({
      productionPostUrl: 'https://prod.example/a/',
      branchDeleted: true,
    });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(approveArticleReview).toHaveBeenCalledWith(7, 201);
  });

  it('ブランチ削除に失敗しても(branchDeleted=false)成功として返す', async () => {
    approveArticleReview.mockResolvedValue({ productionPostUrl: 'https://prod.example/a/', branchDeleted: false });

    await expect(approveArticleReviewAction(7, 201)).resolves.toEqual({
      productionPostUrl: 'https://prod.example/a/',
      branchDeleted: false,
    });
  });

  it('API失敗はサーバが返した理由を error として返し、URLは返さない', async () => {
    approveArticleReview.mockRejectedValue(new Error('APIエラー (409): レビュー中ではありません'));

    await expect(approveArticleReviewAction(7, 201)).resolves.toEqual({ error: 'APIエラー (409): レビュー中ではありません' });
  });

  it('Error 以外が投げられても文字列化して error に入れる', async () => {
    approveArticleReview.mockRejectedValue('boom');

    await expect(approveArticleReviewAction(7, 201)).resolves.toEqual({ error: 'boom' });
  });

  it('管理者でないときはAPIへ到達しない', async () => {
    requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT'));

    await expect(approveArticleReviewAction(7, 201)).rejects.toThrow('NEXT_REDIRECT');
    expect(approveArticleReview).not.toHaveBeenCalled();
  });
});

describe('rejectArticleReviewAction(issue #1346)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('管理者を確認してから差し戻しAPIを指摘事項つきで呼び、rejected を返す', async () => {
    rejectArticleReview.mockResolvedValue({ prNumber: 201, state: 'CHANGES_REQUESTED', commentId: 5 });

    await expect(rejectArticleReviewAction(7, 201, '見出しを直してください')).resolves.toEqual({ rejected: true });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(rejectArticleReview).toHaveBeenCalledWith(7, 201, '見出しを直してください');
  });

  it.each([[''], ['   '], ['\n\t ']])('指摘事項が空白だけ(%j)ならAPIを呼ばず入力が必要な旨を error で返す', async (feedback) => {
    const result = await rejectArticleReviewAction(7, 201, feedback);

    expect(result).toEqual({ error: expect.stringContaining('指摘事項') });
    expect(rejectArticleReview).not.toHaveBeenCalled();
  });

  it('API失敗はサーバが返した理由を error として返し、rejected は返さない', async () => {
    rejectArticleReview.mockRejectedValue(new Error('APIエラー (409): レビュー中ではありません'));

    await expect(rejectArticleReviewAction(7, 201, '直して')).resolves.toEqual({ error: 'APIエラー (409): レビュー中ではありません' });
  });

  it('Error 以外が投げられても文字列化して error に入れる', async () => {
    rejectArticleReview.mockRejectedValue('boom');

    await expect(rejectArticleReviewAction(7, 201, '直して')).resolves.toEqual({ error: 'boom' });
  });

  it('管理者でないときはAPIへ到達しない', async () => {
    requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT'));

    await expect(rejectArticleReviewAction(7, 201, '直して')).rejects.toThrow('NEXT_REDIRECT');
    expect(rejectArticleReview).not.toHaveBeenCalled();
  });
});
