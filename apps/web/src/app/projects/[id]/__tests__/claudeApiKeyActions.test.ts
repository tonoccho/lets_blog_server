/**
 * @jest-environment node
 */

/**
 * issue #1507: Claude(Anthropic)のプロジェクト単位APIキーを保存・削除するServer Actionの検証。
 * `../actions` は他のコンポーネントテストから常にモックされるため、ここではモックせずに直接importする。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const setProjectClaudeApiKey = jest.fn();
const clearProjectClaudeApiKey = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  setProjectClaudeApiKey: (...args: unknown[]) => setProjectClaudeApiKey(...args),
  clearProjectClaudeApiKey: (...args: unknown[]) => clearProjectClaudeApiKey(...args),
}));

import { clearClaudeApiKeyAction, setClaudeApiKeyAction } from '../actions';

describe('Claude APIキーのServer Action(issue #1507)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('setClaudeApiKeyActionはトリムしたキーを保存して空オブジェクトを返す', async () => {
    setProjectClaudeApiKey.mockResolvedValue(undefined);

    await expect(setClaudeApiKeyAction(7, '  sk-abc  ')).resolves.toEqual({});

    expect(requireAdminSession).toHaveBeenCalled();
    expect(setProjectClaudeApiKey).toHaveBeenCalledWith(7, 'sk-abc');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
  });

  it('setClaudeApiKeyActionは空・空白のキーを保存せずエラーを返す', async () => {
    await expect(setClaudeApiKeyAction(7, '')).resolves.toEqual({ error: 'APIキーを入力してください。' });
    await expect(setClaudeApiKeyAction(7, '   ')).resolves.toEqual({ error: 'APIキーを入力してください。' });

    expect(setProjectClaudeApiKey).not.toHaveBeenCalled();
  });

  it('setClaudeApiKeyActionは失敗をerrorとして返す(Error以外も文字列にする)', async () => {
    setProjectClaudeApiKey.mockRejectedValueOnce(new Error('APIエラー (403)'));
    await expect(setClaudeApiKeyAction(7, 'k')).resolves.toEqual({ error: 'APIエラー (403)' });

    setProjectClaudeApiKey.mockRejectedValueOnce('plain');
    await expect(setClaudeApiKeyAction(7, 'k')).resolves.toEqual({ error: 'plain' });
  });

  it('clearClaudeApiKeyActionは削除して空オブジェクトを返し、失敗はerrorとして返す', async () => {
    clearProjectClaudeApiKey.mockResolvedValue(undefined);
    await expect(clearClaudeApiKeyAction(7)).resolves.toEqual({});
    expect(requireAdminSession).toHaveBeenCalled();
    expect(clearProjectClaudeApiKey).toHaveBeenCalledWith(7);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');

    clearProjectClaudeApiKey.mockRejectedValueOnce(new Error('boom'));
    await expect(clearClaudeApiKeyAction(7)).resolves.toEqual({ error: 'boom' });

    clearProjectClaudeApiKey.mockRejectedValueOnce('plain');
    await expect(clearClaudeApiKeyAction(7)).resolves.toEqual({ error: 'plain' });
  });
});
