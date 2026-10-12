/**
 * @jest-environment node
 *
 * issue #1768: `GET /api/generation-jobs` からサイトキーのジョブを探す支援関数
 * (`./support/provisionedSiteJob`)の単体テスト。偽の APIRequestContext を使う。
 * 実行方法は `provisionedSiteRow.test.ts` と同じ。
 */
import type { APIRequestContext } from '@playwright/test';
import { makeProvisionedJobFinder } from './support/provisionedSiteJob';

type Resp = { ok: boolean; status?: number; body?: unknown };

function fakeRequest(routes: Record<string, Resp>): APIRequestContext {
  return {
    get: async (url: string) => {
      const r = routes[url];
      if (!r) throw new Error(`unexpected ${url}`);
      return { ok: () => r.ok, status: () => r.status ?? 200, json: async () => r.body };
    },
  } as unknown as APIRequestContext;
}

const headers = async () => ({ Authorization: 'Bearer t' });
const detail = (id: number, key: string, status: string, resultPayload: string | null) => ({
  ok: true,
  body: { id, status, requestPayload: `{"siteKey":"${key}"}`, resultPayload },
});

describe('makeProvisionedJobFinder', () => {
  it('一覧の取得に失敗したら例外にする', async () => {
    const find = makeProvisionedJobFinder(fakeRequest({ '/api/generation-jobs': { ok: false, status: 503 } }), headers);
    await expect(find('k')).rejects.toThrow(/503/);
  });

  it('サイトキーを要求に持つ site_provisioning ジョブの状態と error を返す', async () => {
    const request = fakeRequest({
      '/api/generation-jobs': { ok: true, body: [{ id: 1, type: 'image_generation' }, { id: 2, type: 'site_provisioning' }, { id: 3, type: 'site_provisioning' }, { id: 4, type: 'site_provisioning' }] },
      '/api/generation-jobs/2': { ok: false },
      '/api/generation-jobs/3': detail(3, 'other', 'running', null),
      '/api/generation-jobs/4': detail(4, 'k', 'failed', '{"error":"満杯","errorType":"queue_full"}'),
    });
    expect(await makeProvisionedJobFinder(request, headers)('k')).toEqual({ id: 4, status: 'failed', error: '満杯' });
  });

  it('見つからなければ null', async () => {
    const request = fakeRequest({ '/api/generation-jobs': { ok: true, body: [] } });
    expect(await makeProvisionedJobFinder(request, headers)('k')).toBeNull();
  });

  it.each([
    [null, null],
    ['{"error":5}', null],
    ['not json', 'not json'],
  ])('結果ペイロード %p の error は %p', async (payload, expected) => {
    const request = fakeRequest({
      '/api/generation-jobs': { ok: true, body: [{ id: 9, type: 'site_provisioning' }] },
      '/api/generation-jobs/9': detail(9, 'k', 'done', payload),
    });
    expect((await makeProvisionedJobFinder(request, headers)('k'))?.error).toBe(expected);
  });
});
