import * as http from 'http';
import * as https from 'https';
import { AddressInfo } from 'net';
import { httpRequest, HttpRequestOptions } from '../httpClient';
import { LocalHttpServer } from './support/localHttpServer';
import { SELF_SIGNED_CERT, SELF_SIGNED_KEY } from './support/selfSignedCert';

/**
 * httpRequest の検証。自前の HTTP クライアントなので差し替えず、テスト内で実際に待ち受ける
 * サーバー(平文 HTTP と自己署名 HTTPS)へ通す(issue #1839: モックするのは外部通信の向こう側だけ)。
 */

function options(overrides: Partial<HttpRequestOptions> = {}): HttpRequestOptions {
  return { method: 'GET', headers: {}, signal: new AbortController().signal, allowInsecureTls: false, ...overrides };
}

describe('httpRequest(fetch経路)', () => {
  let server: LocalHttpServer;
  let rawServer: http.Server;
  let rawUrl: string;

  beforeAll(async () => {
    server = await LocalHttpServer.start();
    // X-Correlation-Id を付けて返すサーバー(LocalHttpServer は固定ヘッダなので別に立てる)。
    rawServer = http.createServer((req, res) => {
      res.writeHead(503, { 'X-Correlation-Id': 'corr-abc', 'content-type': 'application/json' });
      res.end('{}');
    });
    await new Promise<void>((resolve) => rawServer.listen(0, '127.0.0.1', resolve));
    rawUrl = `http://127.0.0.1:${(rawServer.address() as AddressInfo).port}`;
  });

  afterAll(async () => {
    await server.stop();
    rawServer.closeAllConnections();
    await new Promise<void>((resolve) => rawServer.close(() => resolve()));
  });

  it('応答ヘッダをヘッダ名の大文字小文字を問わず読める', async () => {
    const res = await httpRequest(`${rawUrl}/api/ai/draft`, options());

    expect(res.status).toBe(503);
    expect(res.ok).toBe(false);
    expect(res.header('X-Correlation-Id')).toBe('corr-abc');
    expect(res.header('x-correlation-id')).toBe('corr-abc');
  });

  it('存在しないヘッダはundefinedを返す(nullを拡張内部へ持ち込まない)', async () => {
    server.respondWith({ status: 200, body: '{}' });

    const res = await httpRequest(`${server.url}/api/sites`, options());

    expect(res.header('X-Correlation-Id')).toBeUndefined();
    expect(res.ok).toBe(true);
  });

  it('メソッド・ヘッダ・本文を送り、statusText と text で本文を読める', async () => {
    server.recorded.length = 0;
    server.respondWith({ status: 200, body: '{"a":1}' });

    const res = await httpRequest(
      `${server.url}/echo?x=1`,
      options({ method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: 'hello' })
    );

    expect(server.recorded).toEqual([{ path: '/echo?x=1', method: 'POST', body: 'hello' }]);
    expect(res.statusText).toBe('OK');
    expect(await res.text()).toBe('{"a":1}');
  });

  it('json と arrayBuffer で本文を読める', async () => {
    server.respondWith({ status: 200, body: '{"a":1}' });

    const asJson = await httpRequest(`${server.url}/j`, options());
    const asBytes = await httpRequest(`${server.url}/b`, options());

    expect(await asJson.json()).toEqual({ a: 1 });
    expect(Buffer.from(await asBytes.arrayBuffer()).toString('utf-8')).toBe('{"a":1}');
  });

  it('allowInsecureTls でも https でなければ fetch 経路を使う', async () => {
    server.respondWith({ status: 200, body: 'plain' });

    const res = await httpRequest(`${server.url}/p`, options({ allowInsecureTls: true }));

    expect(await res.text()).toBe('plain');
  });

  it('中断された fetch は AbortError で拒否される', async () => {
    server.respondWith({ status: 200, hang: true });
    const controller = new AbortController();
    setTimeout(() => controller.abort(), 50);

    await expect(httpRequest(`${server.url}/slow`, options({ signal: controller.signal }))).rejects.toMatchObject({
      name: 'AbortError',
    });
  });
});

/**
 * 自己署名証明書を許容する経路(allowInsecureTls=true かつ https://)。
 * 実際に TLS を終端するサーバー(自己署名)へ node:https で通す。
 */
describe('httpRequest(自己署名を許容する node:https 経路)', () => {
  interface Seen {
    method?: string;
    url?: string;
    body: string;
    contentType?: string;
  }

  let tls: https.Server;
  let base: string;
  let seen: Seen[];
  let behavior: (req: http.IncomingMessage, res: http.ServerResponse) => void;

  beforeAll(async () => {
    tls = https.createServer({ key: SELF_SIGNED_KEY, cert: SELF_SIGNED_CERT }, (req, res) => {
      const chunks: Buffer[] = [];
      req.on('data', (c: Buffer) => chunks.push(c));
      req.on('end', () => {
        seen.push({
          method: req.method,
          url: req.url,
          body: Buffer.concat(chunks).toString('utf-8'),
          contentType: req.headers['content-type'],
        });
        behavior(req, res);
      });
    });
    await new Promise<void>((resolve) => tls.listen(0, '127.0.0.1', resolve));
    base = `https://127.0.0.1:${(tls.address() as AddressInfo).port}`;
  });

  afterAll(async () => {
    tls.closeAllConnections();
    await new Promise<void>((resolve) => tls.close(() => resolve()));
  });

  beforeEach(() => {
    seen = [];
    behavior = (_req, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"ok":true}');
    };
  });

  it('自己署名証明書のサーバーへ接続でき、リクエスト内容と応答本文を扱える', async () => {
    const res = await httpRequest(
      `${base}/path/to?q=1`,
      options({ method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: 'payload', allowInsecureTls: true })
    );

    expect(seen).toEqual([{ method: 'POST', url: '/path/to?q=1', body: 'payload', contentType: 'text/plain' }]);
    expect(res.status).toBe(200);
    expect(res.ok).toBe(true);
    expect(res.statusText).toBe('OK');
    expect(await res.text()).toBe('{"ok":true}');
  });

  it('json と arrayBuffer で本文を読める', async () => {
    const a = await httpRequest(`${base}/a`, options({ allowInsecureTls: true }));
    const b = await httpRequest(`${base}/b`, options({ allowInsecureTls: true }));

    expect(await a.json()).toEqual({ ok: true });
    expect(Buffer.from(await b.arrayBuffer()).toString('utf-8')).toBe('{"ok":true}');
  });

  it('本文を持たないリクエストは本文を書き込まない', async () => {
    await httpRequest(`${base}/nobody`, options({ allowInsecureTls: true }));

    expect(seen[0].body).toBe('');
  });

  it('Buffer の本文も送れる', async () => {
    await httpRequest(
      `${base}/buf`,
      options({ method: 'PUT', body: Buffer.from([0x68, 0x69]), allowInsecureTls: true })
    );

    expect(seen[0].body).toBe('hi');
  });

  it('4xx/5xx は ok=false で、ヘッダは大文字小文字を問わず読める', async () => {
    behavior = (_req, res) => {
      res.writeHead(502, 'Bad Gateway', { 'X-Correlation-Id': 'corr-xyz' });
      res.end();
    };

    const res = await httpRequest(`${base}/fail`, options({ allowInsecureTls: true }));

    expect(res.status).toBe(502);
    expect(res.ok).toBe(false);
    expect(res.statusText).toBe('Bad Gateway');
    expect(res.header('X-Correlation-Id')).toBe('corr-xyz');
    expect(res.header('x-correlation-id')).toBe('corr-xyz');
    expect(res.header('x-absent')).toBeUndefined();
  });

  it('同名ヘッダが複数あるときは ", " で連結して返す', async () => {
    behavior = (_req, res) => {
      res.writeHead(200, { 'Set-Cookie': ['a=1', 'b=2'] });
      res.end();
    };

    const res = await httpRequest(`${base}/cookies`, options({ allowInsecureTls: true }));

    expect(res.header('set-cookie')).toBe('a=1, b=2');
  });

  it('接続できない場合は拒否される', async () => {
    const closed = https.createServer({ key: SELF_SIGNED_KEY, cert: SELF_SIGNED_CERT });
    await new Promise<void>((resolve) => closed.listen(0, '127.0.0.1', resolve));
    const port = (closed.address() as AddressInfo).port;
    await new Promise<void>((resolve) => closed.close(() => resolve()));

    await expect(httpRequest(`https://127.0.0.1:${port}/x`, options({ allowInsecureTls: true }))).rejects.toMatchObject({
      code: 'ECONNREFUSED',
    });
  });

  it('URL にポートが無いときは接続前に中断済みの signal で、ネットワークへ出ずに拒否される', async () => {
    // 既定の 443 へ実際に接続すると環境(待ち受けの有無)で結果が変わるので、中断済みの signal で接続前に止める。
    const controller = new AbortController();
    controller.abort();

    await expect(
      httpRequest('https://127.0.0.1/default-port', options({ signal: controller.signal, allowInsecureTls: true }))
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(seen).toEqual([]);
  });

  it('応答の途中で接続が切れると本文の読み取り前に拒否される', async () => {
    behavior = (_req, res) => {
      res.writeHead(200, { 'content-length': '1000' });
      res.write('x', () => res.socket?.destroy());
    };

    await expect(httpRequest(`${base}/broken`, options({ allowInsecureTls: true }))).rejects.toBeDefined();
  });

  it('応答待ちの間に中断すると AbortError で拒否される', async () => {
    behavior = () => {
      /* 応答を返さない */
    };
    const controller = new AbortController();
    setTimeout(() => controller.abort(), 50);

    await expect(
      httpRequest(`${base}/slow`, options({ signal: controller.signal, allowInsecureTls: true }))
    ).rejects.toMatchObject({ name: 'AbortError', message: 'The operation was aborted' });
  });

  it('すでに中断済みの signal ではリクエストが失敗する', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(
      httpRequest(`${base}/pre`, options({ signal: controller.signal, allowInsecureTls: true }))
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(seen).toEqual([]);
  });
});
