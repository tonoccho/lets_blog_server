/**
 * @jest-environment node
 *
 * ダッシュボードの 4 つの Route Handler(接続サービス / コンテナの稼働状況の JSON と SSE)。
 * ブラウザは Authorization ヘッダーを付けられないため、サーバー側が gateway へ中継する(issue #280 / #198 / #876)。
 */
// 自前の @/lib/apiClient・@/lib/session・@/lib/auth は差し替えない。差し替えるのは Next.js の実行基盤
// (next-auth・next/headers・next/server の after)と、外部との通信(fetch)だけ(#1832)。
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => ({
  ...jest.requireActual('next/server'),
  ...require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule,
}));
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);

import {
  installFetch,
  jsonResponse,
  resetServerRuntime,
  textResponse,
} from '@/lib/__tests__/testUtils/serverRuntime';

import { GET as getServices } from '../service-status/route';
import { GET as streamServices } from '../service-status/stream/route';
import { GET as getContainers } from '../container-status/route';
import { GET as streamContainers } from '../container-status/stream/route';

beforeEach(() => {
  resetServerRuntime();
});

describe.each([
  ['service-status', getServices, streamServices, '接続サービスの状態取得に失敗しました。'],
  ['container-status', getContainers, streamContainers, 'コンテナの状態取得に失敗しました。'],
])('/api/dashboard/%s', (name, getJson, getStream, fallbackMessage) => {
  const jsonPath = `GET /api/dashboard/${name}`;
  const streamPath = `GET /api/dashboard/${name}/stream`;

  it('gateway が返した状態の一覧を JSON のまま返す', async () => {
    const statuses = [{ id: 'a', name: 'A', status: 'NORMAL' }];
    const fetchMock = installFetch({ [jsonPath]: () => jsonResponse(statuses) });

    const res = await getJson();

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual(statuses);
    expect(fetchMock).toHaveBeenCalledWith(
      `https://localhost/api/dashboard/${name}`,
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer access-token' }) })
    );
  });

  it('gateway が失敗したら 500 とエラーメッセージを返す', async () => {
    installFetch({ [jsonPath]: () => textResponse('down', 503) });

    const res = await getJson();

    expect(res.status).toBe(500);
    expect((await res.json()).error).toContain('503');
  });

  it('Error 以外が投げられたら既定のメッセージで 500 を返す', async () => {
    installFetch({
      [jsonPath]: () => {
        throw 'network down';
      },
    });

    const res = await getJson();

    expect(res.status).toBe(500);
    expect((await res.json()).error).toBe(fallbackMessage);
  });

  it('SSE の応答本体を、イベントストリームのヘッダーを付けてそのまま中継する', async () => {
    const fetchMock = installFetch({
      [streamPath]: () => new Response('event: status\ndata: []\n\n', { status: 200 }),
    });

    const res = await getStream();

    expect(res.status).toBe(200);
    expect(res.headers.get('Content-Type')).toBe('text/event-stream');
    expect(res.headers.get('Cache-Control')).toBe('no-cache, no-transform');
    expect(res.headers.get('Connection')).toBe('keep-alive');
    expect(await res.text()).toBe('event: status\ndata: []\n\n');
    expect(fetchMock).toHaveBeenCalledWith(
      `https://localhost/api/dashboard/${name}/stream`,
      expect.objectContaining({ headers: expect.objectContaining({ Accept: 'text/event-stream' }) })
    );
  });

  it('gateway が失敗応答を返したら、そのステータスを本体なしで返す', async () => {
    installFetch({ [streamPath]: () => new Response('nope', { status: 503 }) });

    const res = await getStream();

    expect(res.status).toBe(503);
    expect(await res.text()).toBe('');
  });

  it('本体の無い成功応答でも、ストリームとしては中継せず本体なしで返す', async () => {
    installFetch({ [streamPath]: () => new Response(null, { status: 200 }) });

    const res = await getStream();

    expect(res.status).toBe(200);
    expect(res.headers.get('Content-Type')).not.toBe('text/event-stream');
  });

  it('失敗応答にステータスが無ければ 502 を返す', async () => {
    installFetch({ [streamPath]: () => ({ ok: false, status: 0, body: null, statusText: '' }) as unknown as Response });

    const res = await getStream();

    expect(res.status).toBe(502);
  });
});
