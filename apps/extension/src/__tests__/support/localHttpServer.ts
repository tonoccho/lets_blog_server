/**
 * テスト内で実際に待ち受ける HTTP サーバー(issue #1839)。
 *
 * 拡張の HTTP 通信(httpClient.ts → 組み込み fetch / node:https)の向こう側にいる外部サービス
 * (Keycloak・gateway)の代役。自前のモジュール(httpClient.ts)は差し替えず、実物の経路を通して
 * この 127.0.0.1 のサーバーへ送る。外部プロセスは要らない。
 */
import * as http from 'http';
import { AddressInfo } from 'net';

export interface RecordedRequest {
  path: string;
  method: string;
  body: string;
}

export interface Reply {
  status: number;
  /** 応答本文。省略すると空。 */
  body?: string;
  /** true なら content-length より短く送って接続を切り、本文の読み取りだけを失敗させる。 */
  brokenBody?: boolean;
  /** true なら応答を返さない(呼び出し側の AbortSignal による中断を試すため)。 */
  hang?: boolean;
}

export class LocalHttpServer {
  public readonly recorded: RecordedRequest[] = [];
  private reply: Reply = { status: 200 };

  private constructor(
    private readonly server: http.Server,
    public readonly url: string
  ) {}

  static async start(): Promise<LocalHttpServer> {
    let instance: LocalHttpServer | undefined;
    const server = http.createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on('data', (chunk: Buffer) => chunks.push(chunk));
      req.on('end', () => {
        instance?.recorded.push({
          path: req.url ?? '',
          method: req.method ?? '',
          body: Buffer.concat(chunks).toString('utf-8'),
        });
        const reply = instance?.reply ?? { status: 500 };
        if (reply.hang) {
          return;
        }
        if (reply.brokenBody) {
          res.writeHead(reply.status, { 'content-length': '1000' });
          res.write('x', () => res.socket?.destroy());
          return;
        }
        res.writeHead(reply.status, { 'content-type': 'application/json' });
        res.end(reply.body ?? '');
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const { port } = server.address() as AddressInfo;
    instance = new LocalHttpServer(server, `http://127.0.0.1:${port}`);
    return instance;
  }

  respondWith(reply: Reply): void {
    this.reply = reply;
  }

  async stop(): Promise<void> {
    this.server.closeAllConnections();
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
  }
}

/** 待ち受けていないポートの URL(接続拒否=サーバーへ到達できない状況を作る)。 */
export async function unreachableUrl(): Promise<string> {
  const server = await LocalHttpServer.start();
  const url = server.url;
  await server.stop();
  return url;
}
