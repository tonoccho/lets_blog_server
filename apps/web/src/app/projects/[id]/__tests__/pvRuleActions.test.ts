/**
 * @jest-environment node
 */

/**
 * issue #1578: 設定画面の PV 達成ルールの Server Action(追加 / 削除 / 再送)。
 * いずれも admin 限定で、非 admin のときはバックエンドへ到達しない。入力の検証はバックエンドへ送る前に行う。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const addRule = jest.fn();
const deleteRule = jest.fn();
const resend = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  addProjectPvRule: (...args: unknown[]) => addRule(...args),
  deleteProjectPvRule: (...args: unknown[]) => deleteRule(...args),
  resendProjectPvRules: (...args: unknown[]) => resend(...args),
}));

import { addProjectPvRuleAction, deleteProjectPvRuleAction, resendProjectPvRulesAction } from '../pvRuleActions';

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}

const sent = { addable: true, reason: null, rules: [], send: { state: 'SENT', error: null, at: null } };
const failed = { addable: true, reason: null, rules: [], send: { state: 'FAILED', error: '本番サイトに届きません', at: null } };

describe('PV 達成ルールの Server Action(issue #1578)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  describe('addProjectPvRuleAction', () => {
    it('期間と閾値を送ってルールを追加し、設定画面を再検証する', async () => {
      addRule.mockResolvedValue(sent);

      const result = await addProjectPvRuleAction(7, {}, form({ period: 'total', threshold: ' 5000 ' }));

      expect(result).toEqual({ success: true });
      expect(requireAdminSession).toHaveBeenCalled();
      expect(addRule).toHaveBeenCalledWith(7, { period: 'total', threshold: 5000 });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('本番サイトへの送信に失敗しても保存はされているので、成功として返し再検証する(失敗は一覧側に出る)', async () => {
      addRule.mockResolvedValue(failed);

      expect(await addProjectPvRuleAction(7, {}, form({ period: 'daily', threshold: '100' }))).toEqual({ success: true });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('期間が daily でも total でもなければAPIを呼ばずエラーを返す', async () => {
      const result = await addProjectPvRuleAction(7, {}, form({ period: 'weekly', threshold: '100' }));

      expect(result.error).toContain('期間');
      expect(addRule).not.toHaveBeenCalled();
    });

    it('期間が未指定でもAPIを呼ばずエラーを返す', async () => {
      const result = await addProjectPvRuleAction(7, {}, form({ threshold: '100' }));

      expect(result.error).toContain('期間');
      expect(addRule).not.toHaveBeenCalled();
    });

    it.each(['', 'abc', '0', '-5', '1.5'])('閾値が「%s」ならAPIを呼ばずエラーを返す', async (threshold) => {
      const result = await addProjectPvRuleAction(7, {}, form({ period: 'daily', threshold }));

      expect(result.error).toContain('閾値');
      expect(addRule).not.toHaveBeenCalled();
    });

    it('閾値が未指定でもエラーを返す', async () => {
      const result = await addProjectPvRuleAction(7, {}, form({ period: 'daily' }));

      expect(result.error).toContain('閾値');
    });

    it('GA が未連携などAPIの拒否はエラーとして返す(Errorでない例外も文字列化する)', async () => {
      addRule.mockRejectedValueOnce(new Error('GA が連携されていません'));
      expect(await addProjectPvRuleAction(7, {}, form({ period: 'daily', threshold: '100' }))).toEqual({
        error: 'GA が連携されていません',
      });

      addRule.mockRejectedValueOnce('boom');
      expect(await addProjectPvRuleAction(7, {}, form({ period: 'daily', threshold: '100' }))).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(addProjectPvRuleAction(7, {}, form({ period: 'daily', threshold: '100' }))).rejects.toThrow('NEXT_REDIRECT');
      expect(addRule).not.toHaveBeenCalled();
    });
  });

  describe('deleteProjectPvRuleAction', () => {
    it('ルールを削除し、設定画面を再検証する', async () => {
      deleteRule.mockResolvedValue(sent);

      expect(await deleteProjectPvRuleAction(7, 'r3')).toEqual({ success: true });
      expect(deleteRule).toHaveBeenCalledWith(7, 'r3');
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('APIの失敗はエラーとして返す(Errorでない例外も文字列化する)', async () => {
      deleteRule.mockRejectedValueOnce(new Error('見つかりません'));
      expect(await deleteProjectPvRuleAction(7, 'r3')).toEqual({ error: '見つかりません' });
      deleteRule.mockRejectedValueOnce('boom');
      expect(await deleteProjectPvRuleAction(7, 'r3')).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(deleteProjectPvRuleAction(7, 'r3')).rejects.toThrow('NEXT_REDIRECT');
      expect(deleteRule).not.toHaveBeenCalled();
    });
  });

  describe('resendProjectPvRulesAction', () => {
    it('再送が届いたら成功を返し、設定画面を再検証する', async () => {
      resend.mockResolvedValue(sent);

      expect(await resendProjectPvRulesAction(7)).toEqual({ success: true });
      expect(resend).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('再送しても届かなければ失敗の理由を返し、状態を見せるため再検証する', async () => {
      resend.mockResolvedValue(failed);

      expect(await resendProjectPvRulesAction(7)).toEqual({ error: '本番サイトに届きません' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('理由が無い失敗にも既定の文言を返す', async () => {
      resend.mockResolvedValue({ ...failed, send: { state: 'FAILED', error: null, at: null } });

      expect((await resendProjectPvRulesAction(7)).error).toContain('再送');
    });

    it('APIに届かなければエラーを返す(Errorでない例外も文字列化する)', async () => {
      resend.mockRejectedValueOnce(new Error('接続失敗'));
      expect(await resendProjectPvRulesAction(7)).toEqual({ error: '接続失敗' });
      resend.mockRejectedValueOnce('boom');
      expect(await resendProjectPvRulesAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(resendProjectPvRulesAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(resend).not.toHaveBeenCalled();
    });
  });
});
