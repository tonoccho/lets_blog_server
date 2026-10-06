/**
 * @jest-environment node
 */

/**
 * issue #1583: 設定画面の告知文テンプレートの Server Action(保存 / 再送)。
 * いずれも admin 限定で、非 admin のときはバックエンドへ到達しない。入力の検証(文字数・差し込み項目)はバックエンドが行い、
 * その拒否の理由をそのまま画面へ返す。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const save = jest.fn();
const resend = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  saveProjectSnsTemplates: (...args: unknown[]) => save(...args),
  resendProjectSnsTemplates: (...args: unknown[]) => resend(...args),
}));

import { resendProjectSnsTemplatesAction, saveProjectSnsTemplatesAction } from '../snsTemplateActions';

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}

const sent = { publishTemplate: '', pvTemplate: '', send: { state: 'SENT', error: null, at: null } };
const failed = { publishTemplate: '', pvTemplate: '', send: { state: 'FAILED', error: '本番サイトに届きません', at: null } };

describe('告知文テンプレートの Server Action(issue #1583)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  describe('saveProjectSnsTemplatesAction', () => {
    it('公開時と PV 達成時のテンプレートを別々に送って保存し、設定画面を再検証する', async () => {
      save.mockResolvedValue(sent);

      const result = await saveProjectSnsTemplatesAction(
        7,
        {},
        form({ publishTemplate: '【新着】{title} {url}', pvTemplate: '{period}で{threshold}PV {url}' })
      );

      expect(result).toEqual({ success: true });
      expect(requireAdminSession).toHaveBeenCalled();
      expect(save).toHaveBeenCalledWith(7, {
        publishTemplate: '【新着】{title} {url}',
        pvTemplate: '{period}で{threshold}PV {url}',
      });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('空のテンプレート(既定の告知文を使う)も、そのまま空として送る', async () => {
      save.mockResolvedValue(sent);

      expect(await saveProjectSnsTemplatesAction(7, {}, form({ publishTemplate: '', pvTemplate: '' }))).toEqual({
        success: true,
      });
      expect(save).toHaveBeenCalledWith(7, { publishTemplate: '', pvTemplate: '' });
    });

    it('フォームに欄が無ければ空として送る', async () => {
      save.mockResolvedValue(sent);

      await saveProjectSnsTemplatesAction(7, {}, form({}));

      expect(save).toHaveBeenCalledWith(7, { publishTemplate: '', pvTemplate: '' });
    });

    it('本番サイトへの送信に失敗しても保存はされているので、成功として返す(失敗は送信状態に出る)', async () => {
      save.mockResolvedValue(failed);

      expect(await saveProjectSnsTemplatesAction(7, {}, form({ publishTemplate: 'a', pvTemplate: 'b' }))).toEqual({
        success: true,
      });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('APIの拒否はエラーとして返す(Errorでない例外も文字列化する)', async () => {
      save.mockRejectedValueOnce(new Error('公開時のテンプレートに {period} は使えません'));
      expect(await saveProjectSnsTemplatesAction(7, {}, form({ publishTemplate: '{period}' }))).toEqual({
        error: '公開時のテンプレートに {period} は使えません',
      });

      save.mockRejectedValueOnce('boom');
      expect(await saveProjectSnsTemplatesAction(7, {}, form({ publishTemplate: 'x' }))).toEqual({ error: 'boom' });
      expect(revalidatePath).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(saveProjectSnsTemplatesAction(7, {}, form({ publishTemplate: 'a' }))).rejects.toThrow('NEXT_REDIRECT');
      expect(save).not.toHaveBeenCalled();
    });
  });

  describe('resendProjectSnsTemplatesAction', () => {
    it('再送が届いたら成功を返し、設定画面を再検証する', async () => {
      resend.mockResolvedValue(sent);

      expect(await resendProjectSnsTemplatesAction(7)).toEqual({ success: true });
      expect(resend).toHaveBeenCalledWith(7);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('また届かなければ理由を返し、送信状態を読み直す', async () => {
      resend.mockResolvedValue(failed);

      expect(await resendProjectSnsTemplatesAction(7)).toEqual({ error: '本番サイトに届きません' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('失敗の理由が無ければ既定の文言を返す', async () => {
      resend.mockResolvedValue({ ...failed, send: { state: 'FAILED', error: null, at: null } });

      expect(await resendProjectSnsTemplatesAction(7)).toEqual({ error: '再送に失敗しました。' });
    });

    it('APIの失敗はエラーとして返す(Errorでない例外も文字列化する)', async () => {
      resend.mockRejectedValueOnce(new Error('502'));
      expect(await resendProjectSnsTemplatesAction(7)).toEqual({ error: '502' });
      resend.mockRejectedValueOnce('boom');
      expect(await resendProjectSnsTemplatesAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      requireAdminSession.mockRejectedValue(new Error('NEXT_REDIRECT:/'));

      await expect(resendProjectSnsTemplatesAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(resend).not.toHaveBeenCalled();
    });
  });
});
