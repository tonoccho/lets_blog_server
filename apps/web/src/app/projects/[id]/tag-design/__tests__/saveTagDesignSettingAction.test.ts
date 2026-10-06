/**
 * @jest-environment node
 */

/**
 * issue #1409: 生成結果を「保存」で書き込むとき、FormData(multipart)が改行を CRLF にしてしまう。
 * 保存した内容が生成した内容と一致するよう、保存アクションは改行を LF に揃えて送る。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));

jest.mock('@/lib/session', () => ({
  requireAdminSession: jest.fn().mockResolvedValue({ user: { role: 'admin' } }),
}));

const saveTagDesignSetting = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  saveTagDesignSetting: (...args: unknown[]) => saveTagDesignSetting(...args),
  startTagDesignGenerationJob: jest.fn(),
}));

import { revalidatePath } from 'next/cache';
import { saveTagDesignSettingAction } from '../actions';

function form(fields: Record<string, string>): FormData {
  const fd = new FormData();
  for (const [k, v] of Object.entries(fields)) fd.set(k, v);
  return fd;
}

const base = { projectId: '7', tagType: 'TOC', presetId: 'default', backgroundColor: '#fff', textColor: '#000', accentColor: '#00f' };

beforeEach(() => {
  jest.clearAllMocks();
  saveTagDesignSetting.mockResolvedValue({});
});

describe('saveTagDesignSettingAction(issue #1409)', () => {
  it('CSS・HTMLテンプレートの CRLF / CR を LF に揃えて保存する', async () => {
    const result = await saveTagDesignSettingAction(
      {},
      form({ ...base, customCss: '.a{\r\ncolor:red;\r\n}\r\n', htmlTemplate: '<div>\r\n<p>x</p>\r</div>' }),
    );

    expect(result).toEqual({ success: true });
    expect(saveTagDesignSetting).toHaveBeenCalledWith(
      7,
      'TOC',
      expect.objectContaining({ customCss: '.a{\ncolor:red;\n}', htmlTemplate: '<div>\n<p>x</p>\n</div>' }),
    );
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7/tags');
  });

  it('CSS・HTMLが空なら undefined のまま送る', async () => {
    await saveTagDesignSettingAction({}, form({ ...base, customCss: '', htmlTemplate: '' }));
    const input = saveTagDesignSetting.mock.calls[0][2];
    expect(input.customCss).toBeUndefined();
    expect(input.htmlTemplate).toBeUndefined();
  });

  it('グローバル(projectId 空)は null で保存し、管理画面を再検証する', async () => {
    await saveTagDesignSettingAction({}, form({ ...base, projectId: '' }));
    expect(saveTagDesignSetting.mock.calls[0][0]).toBeNull();
    expect(revalidatePath).toHaveBeenCalledWith('/admin/tag-design');
  });

  it('不正な入力と保存失敗はエラーを返す', async () => {
    await expect(saveTagDesignSettingAction({}, form({ ...base, tagType: '' }))).resolves.toEqual({ error: '不正なリクエストです。' });
    await expect(saveTagDesignSettingAction({}, form({ ...base, projectId: 'x' }))).resolves.toEqual({ error: '不正なリクエストです。' });
    saveTagDesignSetting.mockRejectedValueOnce(new Error('boom'));
    await expect(saveTagDesignSettingAction({}, form(base))).resolves.toEqual({ error: 'boom' });
    saveTagDesignSetting.mockRejectedValueOnce('str');
    await expect(saveTagDesignSettingAction({}, form(base))).resolves.toEqual({ error: 'str' });
  });
});
