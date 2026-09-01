/**
 * 外部依存スタブ(infra/e2e-stubs)への操作(issue #942 / AT-16)。
 * 仕様は docs/ACCEPTANCE_TESTING.md §9 と同じ。apps/web の e2e/support/stubs.ts の拡張版ではなく、
 * 拡張側から使う分だけを持つ。
 */

import * as http from 'http';

export const STUB_PORTS = {
  llm: 18081,
  brave: 18084,
  image: 18085,
  github: 18086,
} as const;

function request(port: number, path: string, body?: string): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        path,
        method: body === undefined ? 'GET' : 'POST',
        headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        res.on('end', () => resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks).toString('utf-8') }));
        res.on('error', reject);
      }
    );
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

/**
 * `@stub` が付いたシナリオの前提確認。起動していなければ**スキップではなく失敗**させる
 * (docs/ACCEPTANCE_TESTING.md §9、#843 の再発防止)。
 */
export async function requireStubs(): Promise<void> {
  for (const [name, port] of Object.entries(STUB_PORTS)) {
    try {
      const res = await request(port, '/health');
      if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      throw new Error(
        `外部依存スタブ ${name}(127.0.0.1:${port})へ到達できません: ${reason}\n` +
          'docker compose -f docker-compose.yml -f docker-compose.e2e-stubs.yml up -d で起動してください。'
      );
    }
  }
}

/**
 * リクエストを指定ステータスで失敗させる。count を省略すると resetStub まで継続する
 * (拡張は取得系を最大3回再試行するため、回数を絞ると再試行が成功してしまう)。
 */
export async function forceStubStatus(port: number, status: number, count?: number): Promise<void> {
  await request(port, '/__control/force', JSON.stringify(count === undefined ? { status } : { status, count }));
}

/** 仕込みを解除する。 */
export async function resetStub(port: number): Promise<void> {
  await request(port, '/__control/reset', '{}');
}

/** スタブが受け取ったリクエスト数(制御・死活を除く)。 */
export async function stubRequestCount(port: number): Promise<number> {
  const res = await request(port, '/__control/state');
  return (JSON.parse(res.body) as { requests: number }).requests;
}
