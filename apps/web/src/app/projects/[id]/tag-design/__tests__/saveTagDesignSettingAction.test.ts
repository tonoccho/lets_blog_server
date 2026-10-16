/**
 * @jest-environment node
 */

/**
 * issue #1409: 生成結果を「保存」で書き込むとき、FormData(multipart)が改行を CRLF にしてしまう。
 * 保存した内容が生成した内容と一致するよう、保存アクションは改行を LF に揃えて送る。
 * 自前の @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { saveTagDesignSettingAction } from '../actions';

function form(fields: Record<string, string>): FormData {
  const fd = new FormData();
  for (const [k, v] of Object.entries(fields)) fd.set(k, v);
  return fd;
}

const base = { projectId: '7', tagType: 'TOC', presetId: 'default', backgroundColor: '#fff', textColor: '#000', accentColor: '#00f' };

let saved: { path: string; body: Record<string, unknown> }[];
let respond: () => Response;

beforeEach(() => {
  saved = [];
  revalidatePath.mockClear();
  resetServerRuntime(ADMIN_SESSION);
  respond = () => jsonResponse({});
  const handler = (url: string, init: RequestInit) => {
    saved.push({ path: new URL(url).pathname, body: JSON.parse(String(init.body)) });
    return respond();
  };
  installFetch({
    'PUT /api/projects/:id/tag-design-settings/:type': handler,
    'PUT /api/tag-design-settings/:type': handler,
  });
});

describe('saveTagDesignSettingAction(issue #1409)', () => {
  it('CSS・HTMLテンプレートの CRLF / CR を LF に揃えて保存する', async () => {
    const result = await saveTagDesignSettingAction(
      {},
      form({ ...base, customCss: '.a{\r\ncolor:red;\r\n}\r\n', htmlTemplate: '<div>\r\n<p>x</p>\r</div>' }),
    );

    expect(result).toEqual({ success: true });
    expect(saved).toHaveLength(1);
    expect(saved[0].path).toBe('/api/projects/7/tag-design-settings/TOC');
    expect(saved[0].body).toMatchObject({ customCss: '.a{\ncolor:red;\n}', htmlTemplate: '<div>\n<p>x</p>\n</div>' });
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7/tags');
  });

  it('CSS・HTMLが空なら送らない(未指定のまま)', async () => {
    await saveTagDesignSettingAction({}, form({ ...base, customCss: '', htmlTemplate: '' }));
    expect(saved[0].body.customCss).toBeUndefined();
    expect(saved[0].body.htmlTemplate).toBeUndefined();
  });

  it('グローバル(projectId 空)はグローバルの入口へ保存し、管理画面を再検証する', async () => {
    await saveTagDesignSettingAction({}, form({ ...base, projectId: '' }));
    expect(saved[0].path).toBe('/api/tag-design-settings/TOC');
    expect(revalidatePath).toHaveBeenCalledWith('/admin/tag-design');
  });

  it('不正な入力と保存失敗はエラーを返す', async () => {
    await expect(saveTagDesignSettingAction({}, form({ ...base, tagType: '' }))).resolves.toEqual({ error: '不正なリクエストです。' });
    await expect(saveTagDesignSettingAction({}, form({ ...base, projectId: 'x' }))).resolves.toEqual({ error: '不正なリクエストです。' });
    expect(saved).toEqual([]);
    respond = () => textResponse('boom', 500);
    await expect(saveTagDesignSettingAction({}, form(base))).resolves.toEqual({ error: 'APIエラー (500): boom' });
    global.fetch = jest.fn().mockRejectedValue('str') as unknown as typeof fetch;
    await expect(saveTagDesignSettingAction({}, form(base))).resolves.toEqual({ error: 'str' });
  });

  it('管理者でなければ / へ戻し、保存しない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(saveTagDesignSettingAction({}, form(base))).rejects.toThrow('NEXT_REDIRECT:/');
    expect(saved).toEqual([]);
  });

  it('フォームに項目が無ければ(tagType なし)不正として返す', async () => {
    await expect(saveTagDesignSettingAction({}, new FormData())).resolves.toEqual({ error: '不正なリクエストです。' });
  });

  it('色・プリセットなどの項目がフォームに無くても、空文字のまま送る(バックエンドが検証する)', async () => {
    await expect(saveTagDesignSettingAction({}, form({ tagType: 'TOC' }))).resolves.toEqual({ success: true });
    expect(saved[0].path).toBe('/api/tag-design-settings/TOC');
    expect(saved[0].body).toEqual({ presetId: '', backgroundColor: '', textColor: '', accentColor: '' });
  });
});
