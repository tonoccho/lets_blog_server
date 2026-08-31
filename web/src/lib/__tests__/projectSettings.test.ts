/**
 * issue #913: プロジェクト単位の設定を「所有サービスから個別に取得する」ようにしたことの検証。
 *
 * それまで `Project` 型は cssSelectorPrefix と画像生成設定8件を持つと宣言していたが、
 * `GET /api/projects/{id}` を処理する project-service の `ProjectResponse` はそれらを返さない
 * (所有者が content-service / media-service のため。#576 / #583)。型だけが「返る」と
 * 言っていたので、保存はできるのに画面には常に空が表示されていた。しかも `?? ""` の
 * フォールバックがあるため画面は壊れず、静かに空になるだけだった。
 *
 * ここでは (a) 各設定が所有サービスのエンドポイントから取れること、
 * (b) `Project` 型に実際には返らないフィールドを再び足していないこと、を固定する。
 */
jest.mock('server-only', () => ({}));
// after() は「レスポンス後に実行」なので、テストでは即時実行に潰す。
jest.mock('next/server', () => ({ after: (fn: () => void) => fn() }));
jest.mock('next/headers', () => ({
  cookies: async () => ({ getAll: () => [], get: () => undefined }),
  headers: async () => new Headers(),
}));
// next-auth/jwt は ESM のため jest がそのままではパースできない。
// 本テストの関心はエンドポイントのパスと戻り値の形なので、トークンは無しでよい。
jest.mock('next-auth/jwt', () => ({
  getToken: jest.fn().mockResolvedValue({ accessToken: 'test-token' }),
}));

import { getProjectContentSettings, getProjectImageSettings } from '@/lib/apiClient';

const fetchMock = jest.fn();
global.fetch = fetchMock as unknown as typeof fetch;

function jsonResponse(body: unknown) {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

describe('プロジェクト単位の設定は所有サービスから取得する (issue #913)', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('cssSelectorPrefix は content-service の /content-settings から取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ cssSelectorPrefix: 'lbs-sample' }));

    const settings = await getProjectContentSettings(42);

    expect(settings.cssSelectorPrefix).toBe('lbs-sample');
    // 1回目が本体の呼び出し。2回目以降は操作ログの記録(after()内)なので数は見ない。
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/projects/42/content-settings');
  });

  it('画像生成設定は media-service の /image-settings から取る', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      projectId: 42,
      imageProvider: 'COMFYUI',
      comfyuiCheckpoint: null,
      defaultNegativePrompt: 'blurry',
      defaultQualityPrompt: 'masterpiece',
      defaultGeneratedImageWidth: 1024,
      defaultGeneratedImageHeight: 768,
      defaultArticleImageLongEdgePx: 1300,
      blockSexualContent: true,
      blockViolentContent: false,
      blockDiscriminatoryContent: true,
    }));

    const settings = await getProjectImageSettings(42);

    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/projects/42/image-settings');
    expect(settings.defaultNegativePrompt).toBe('blurry');
    expect(settings.defaultGeneratedImageWidth).toBe(1024);
    // 不適切コンテンツフィルタは、保存済みの true がそのまま届くこと。
    // 以前は undefined になり、フォームが常に未チェック(= 解除)で表示されていた。
    expect(settings.blockSexualContent).toBe(true);
    expect(settings.blockViolentContent).toBe(false);
  });

  it('未設定のプロジェクトは全項目 null が返る(404にはしない)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      projectId: 7,
      imageProvider: null,
      comfyuiCheckpoint: null,
      defaultNegativePrompt: null,
      defaultQualityPrompt: null,
      defaultGeneratedImageWidth: null,
      defaultGeneratedImageHeight: null,
      defaultArticleImageLongEdgePx: null,
      blockSexualContent: null,
      blockViolentContent: null,
      blockDiscriminatoryContent: null,
    }));

    const settings = await getProjectImageSettings(7);

    expect(settings.projectId).toBe(7);
    expect(settings.defaultNegativePrompt).toBeNull();
  });
});
