/**
 * @jest-environment node
 */

/**
 * issue #1504: Ollama / ComfyUI の接続情報(#1499)と接続先の上書き(#1503)を扱うServer Actionの検証。
 * `../actions` は他のコンポーネントテストから常にモックされるため、ここではモックせずに直接importする。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const listAiConnections = jest.fn();
const getProjectConnections = jest.fn();
const updateProjectConnections = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  listAiConnections: (...args: unknown[]) => listAiConnections(...args),
  getProjectConnections: (...args: unknown[]) => getProjectConnections(...args),
  updateProjectConnections: (...args: unknown[]) => updateProjectConnections(...args),
}));

import {
  fetchAiConnectionsAction,
  fetchProjectConnectionsAction,
  updateProjectConnectionAction,
} from '../actions';

describe('接続情報のServer Action(issue #1504)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('fetchAiConnectionsActionは管理者セッションを要求し、一覧をそのまま返す(失敗は握り潰さず投げる)', async () => {
    listAiConnections.mockResolvedValue([{ provider: 'OLLAMA' }]);
    await expect(fetchAiConnectionsAction(7)).resolves.toEqual([{ provider: 'OLLAMA' }]);
    expect(requireAdminSession).toHaveBeenCalled();
    expect(listAiConnections).toHaveBeenCalledWith(7);

    listAiConnections.mockRejectedValue(new Error('boom'));
    await expect(fetchAiConnectionsAction(7)).rejects.toThrow('boom');
  });

  it('fetchProjectConnectionsActionは管理者セッションを要求し、接続先をそのまま返す(失敗は投げる)', async () => {
    const data = { ollama: { overrideBaseUrl: null, baseUrl: 'http://o', source: 'DATABASE' } };
    getProjectConnections.mockResolvedValue(data);
    await expect(fetchProjectConnectionsAction(7)).resolves.toEqual(data);
    expect(requireAdminSession).toHaveBeenCalled();
    expect(getProjectConnections).toHaveBeenCalledWith(7);

    getProjectConnections.mockRejectedValue(new Error('boom'));
    await expect(fetchProjectConnectionsAction(7)).rejects.toThrow('boom');
  });

  it('updateProjectConnectionActionはOLLAMAの値をollamaBaseUrlだけで送り、応答を返す', async () => {
    const data = { ollama: { overrideBaseUrl: 'http://o', baseUrl: 'http://o', source: 'PROJECT' } };
    updateProjectConnections.mockResolvedValue(data);

    const result = await updateProjectConnectionAction(7, 'OLLAMA', 'http://o');

    expect(requireAdminSession).toHaveBeenCalled();
    expect(updateProjectConnections).toHaveBeenCalledWith(7, { ollamaBaseUrl: 'http://o' });
    expect(result).toEqual({ data });
  });

  it('updateProjectConnectionActionはCOMFYUIの空文字をcomfyuiBaseUrl=""(上書き解除)で送る', async () => {
    updateProjectConnections.mockResolvedValue({});

    await updateProjectConnectionAction(7, 'COMFYUI', '');

    expect(updateProjectConnections).toHaveBeenCalledWith(7, { comfyuiBaseUrl: '' });
  });

  it('updateProjectConnectionActionは失敗をerrorとして返す(Error以外の例外も文字列にする)', async () => {
    updateProjectConnections.mockRejectedValueOnce(new Error('APIエラー (400): bad'));
    await expect(updateProjectConnectionAction(7, 'OLLAMA', 'x')).resolves.toEqual({ error: 'APIエラー (400): bad' });

    updateProjectConnections.mockRejectedValueOnce('plain');
    await expect(updateProjectConnectionAction(7, 'OLLAMA', 'x')).resolves.toEqual({ error: 'plain' });
  });
});
