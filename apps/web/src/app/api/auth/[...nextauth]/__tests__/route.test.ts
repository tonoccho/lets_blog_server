/**
 * @jest-environment node
 *
 * NextAuth の Route Handler(GET / POST が同じハンドラ)。next-auth も @/lib/auth も実物を動かす。
 * 差し替えるのは Next.js の実行基盤の `server-only` と `next/headers`(リクエスト文脈が jest に無い)だけ(#1832)。
 * 認証プロバイダ一覧は外部通信なしで返る。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);

import { cookiesMock, headersMock } from '@/lib/__tests__/testUtils/serverRuntime';

import { NextRequest } from 'next/server';
import { GET, POST } from '../route';

describe('/api/auth/[...nextauth]', () => {
  const env = { ...process.env };
  afterEach(() => {
    process.env = { ...env };
  });

  beforeEach(() => {
    process.env.NEXTAUTH_URL = 'http://localhost';
    process.env.NEXTAUTH_SECRET = 'test-secret';
    cookiesMock.mockResolvedValue({ getAll: () => [], get: () => undefined, set: () => undefined });
    headersMock.mockResolvedValue(new Headers());
  });

  it('GET と POST は同じハンドラである', () => {
    expect(POST).toBe(GET);
  });

  it('GET /api/auth/providers が、設定したプロバイダ(Keycloak)を返す', async () => {
    const request = new NextRequest('http://localhost/api/auth/providers');
    const response = await GET(request, { params: Promise.resolve({ nextauth: ['providers'] }) });

    expect(response.status).toBe(200);
    const body = await response.json();
    expect(Object.keys(body)).toEqual(['keycloak']);
    expect(body.keycloak).toMatchObject({ id: 'keycloak', type: 'oauth' });
  });
});
