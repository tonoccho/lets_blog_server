/**
 * @jest-environment node
 */

/**
 * issue #1583: 設定画面の告知文テンプレートの Server Action(保存 / 再送)。
 * いずれも admin 限定で、非 admin のときはバックエンドへ到達しない。入力の検証(文字数・差し込み項目)はバックエンドが行い、
 * その拒否の理由をそのまま画面へ返す。
 * `@/lib/apiClient`・`@/lib/session` は実物を通し、差し替えるのは Next.js の実行基盤と外部との通信(`fetch`)だけ(#1838)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/navigation', () => ({
  redirect: (url: string) => {
    throw new Error(`NEXT_REDIRECT:${url}`);
  },
}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

import { resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION, USER_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { bodies, failFetchWith, failWith, form, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import { resendProjectSnsTemplatesAction, saveProjectSnsTemplatesAction } from '../snsTemplateActions';

const SAVE = 'PUT /api/projects/7/sns/templates';
const RESEND = 'POST /api/projects/7/sns/templates/resend';

const sent = { publishTemplate: '', pvTemplate: '', send: { state: 'SENT', error: null, at: null } };
const failed = { publishTemplate: '', pvTemplate: '', send: { state: 'FAILED', error: '本番サイトに届きません', at: null } };

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({ [SAVE]: () => jsonResponse(sent), [RESEND]: () => jsonResponse(sent) });
});

describe('告知文テンプレートの Server Action(issue #1583)', () => {
  describe('saveProjectSnsTemplatesAction', () => {
    it('公開時と PV 達成時のテンプレートを別々に送って保存し、設定画面を再検証する', async () => {
      const result = await saveProjectSnsTemplatesAction(
        7,
        {},
        form({ publishTemplate: '【新着】{title} {url}', pvTemplate: '{period}で{threshold}PV {url}' })
      );

      expect(result).toEqual({ success: true });
      expect(bodies(backend, SAVE)).toEqual([
        { publishTemplate: '【新着】{title} {url}', pvTemplate: '{period}で{threshold}PV {url}' },
      ]);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('空のテンプレート(既定の告知文を使う)も、そのまま空として送る', async () => {
      expect(await saveProjectSnsTemplatesAction(7, {}, form({ publishTemplate: '', pvTemplate: '' }))).toEqual({
        success: true,
      });
      expect(bodies(backend, SAVE)).toEqual([{ publishTemplate: '', pvTemplate: '' }]);
    });

    it('フォームに欄が無ければ空として送る', async () => {
      await saveProjectSnsTemplatesAction(7, {}, form({}));

      expect(bodies(backend, SAVE)).toEqual([{ publishTemplate: '', pvTemplate: '' }]);
    });

    it('本番サイトへの送信に失敗しても保存はされているので、成功として返す(失敗は送信状態に出る)', async () => {
      installBackend({ [SAVE]: () => jsonResponse(failed) });

      expect(await saveProjectSnsTemplatesAction(7, {}, form({ publishTemplate: 'a', pvTemplate: 'b' }))).toEqual({
        success: true,
      });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('APIの拒否はエラーとして返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [SAVE]: failWith(400, '公開時のテンプレートに {period} は使えません') });
      expect(await saveProjectSnsTemplatesAction(7, {}, form({ publishTemplate: '{period}' }))).toEqual({
        error: 'APIエラー (400): 公開時のテンプレートに {period} は使えません',
      });

      failFetchWith('boom');
      expect(await saveProjectSnsTemplatesAction(7, {}, form({ publishTemplate: 'x' }))).toEqual({ error: 'boom' });
      expect(revalidatePath).not.toHaveBeenCalled();
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(saveProjectSnsTemplatesAction(7, {}, form({ publishTemplate: 'a' }))).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });

  describe('resendProjectSnsTemplatesAction', () => {
    it('再送が届いたら成功を返し、設定画面を再検証する', async () => {
      expect(await resendProjectSnsTemplatesAction(7)).toEqual({ success: true });
      expect(requestCount(backend)).toBe(1);
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('また届かなければ理由を返し、送信状態を読み直す', async () => {
      installBackend({ [RESEND]: () => jsonResponse(failed) });

      expect(await resendProjectSnsTemplatesAction(7)).toEqual({ error: '本番サイトに届きません' });
      expect(revalidatePath).toHaveBeenCalledWith('/projects/7/settings/sns');
    });

    it('失敗の理由が無ければ既定の文言を返す', async () => {
      installBackend({ [RESEND]: () => jsonResponse({ ...failed, send: { state: 'FAILED', error: null, at: null } }) });

      expect(await resendProjectSnsTemplatesAction(7)).toEqual({ error: '再送に失敗しました。' });
    });

    it('APIの失敗はエラーとして返す(Errorでない例外も文字列化する)', async () => {
      installBackend({ [RESEND]: failWith(502, '502') });
      expect(await resendProjectSnsTemplatesAction(7)).toEqual({ error: 'APIエラー (502): 502' });
      failFetchWith('boom');
      expect(await resendProjectSnsTemplatesAction(7)).toEqual({ error: 'boom' });
    });

    it('非 admin はバックエンドへ到達しない', async () => {
      resetServerRuntime(USER_SESSION);

      await expect(resendProjectSnsTemplatesAction(7)).rejects.toThrow('NEXT_REDIRECT');
      expect(requestCount(backend)).toBe(0);
    });
  });
});
