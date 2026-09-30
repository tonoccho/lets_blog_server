/**
 * @jest-environment node
 */

/**
 * issue #1506: ChatGPT(OpenAI)のプロジェクト単位APIキーを保存・削除するServer Actionの検証。
 * `../actions` は他のコンポーネントテストから常にモックされるため、ここではモックせずに直接importする。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const setProjectOpenAiApiKey = jest.fn();
const clearProjectOpenAiApiKey = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  setProjectOpenAiApiKey: (...args: unknown[]) => setProjectOpenAiApiKey(...args),
  clearProjectOpenAiApiKey: (...args: unknown[]) => clearProjectOpenAiApiKey(...args),
}));

import { clearOpenAiApiKeyAction, setOpenAiApiKeyAction } from '../actions';

describe('ChatGPT APIキーのServer Action(issue #1506)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('setOpenAiApiKeyActionはトリムしたキーを保存して空オブジェクトを返す', async () => {
    setProjectOpenAiApiKey.mockResolvedValue(undefined);

    await expect(setOpenAiApiKeyAction(7, '  sk-abc  ')).resolves.toEqual({});

    expect(requireAdminSession).toHaveBeenCalled();
    expect(setProjectOpenAiApiKey).toHaveBeenCalledWith(7, 'sk-abc');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
  });

  it('setOpenAiApiKeyActionは空・空白のキーを保存せずエラーを返す', async () => {
    await expect(setOpenAiApiKeyAction(7, '')).resolves.toEqual({ error: 'APIキーを入力してください。' });
    await expect(setOpenAiApiKeyAction(7, '   ')).resolves.toEqual({ error: 'APIキーを入力してください。' });

    expect(setProjectOpenAiApiKey).not.toHaveBeenCalled();
  });

  it('setOpenAiApiKeyActionは失敗をerrorとして返す(Error以外も文字列にする)', async () => {
    setProjectOpenAiApiKey.mockRejectedValueOnce(new Error('APIエラー (403)'));
    await expect(setOpenAiApiKeyAction(7, 'k')).resolves.toEqual({ error: 'APIエラー (403)' });

    setProjectOpenAiApiKey.mockRejectedValueOnce('plain');
    await expect(setOpenAiApiKeyAction(7, 'k')).resolves.toEqual({ error: 'plain' });
  });

  it('clearOpenAiApiKeyActionは削除して空オブジェクトを返し、失敗はerrorとして返す', async () => {
    clearProjectOpenAiApiKey.mockResolvedValue(undefined);
    await expect(clearOpenAiApiKeyAction(7)).resolves.toEqual({});
    expect(requireAdminSession).toHaveBeenCalled();
    expect(clearProjectOpenAiApiKey).toHaveBeenCalledWith(7);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');

    clearProjectOpenAiApiKey.mockRejectedValueOnce(new Error('boom'));
    await expect(clearOpenAiApiKeyAction(7)).resolves.toEqual({ error: 'boom' });

    clearProjectOpenAiApiKey.mockRejectedValueOnce('plain');
    await expect(clearOpenAiApiKeyAction(7)).resolves.toEqual({ error: 'plain' });
  });
});
