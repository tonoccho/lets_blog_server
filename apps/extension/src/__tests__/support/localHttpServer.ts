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
  /** 受信したヘッダ(名前は Node が小文字へ正規化したもの)。 */
  headers: Record<string, string>;
}

export type ReplyOrHandler = Reply | ((request: RecordedRequest) => Reply | Promise<Reply>);

export interface Reply {
  status: number;
  /** 応答本文。省略すると空。 */
  body?: string;
  /** true なら content-length より短く送って接続を切り、本文の読み取りだけを失敗させる。 */
  brokenBody?: boolean;
  /** 追加で付ける応答ヘッダ(例: X-Correlation-Id)。 */
  headers?: Record<string, string>;
  /** 応答本文をバイナリで返す(画像の取得用)。指定すると body より優先する。 */
  bodyBytes?: Buffer;
  /**
   * 指定すると、状態行とヘッダだけを先に送り、この Promise が解けてから本文を送る。
   * 「応答ヘッダは届いたが本文の受信が終わっていない」状況を作る。
   */
  bodyGate?: Promise<void>;
  /** true なら応答を返さない(呼び出し側の AbortSignal による中断を試すため)。 */
  hang?: boolean;
}

export class LocalHttpServer {
  public readonly recorded: RecordedRequest[] = [];
  private reply: ReplyOrHandler = { status: 200 };
  /** 応答を返し終える前に、クライアント側が接続を閉じた(中断した)要求の数。 */
  public abortedRequests = 0;
  /** 受信中・応答中の要求の数(応答を返さない `hang` の要求も、クライアントが閉じるまで数える)。 */
  public inFlight = 0;

  private constructor(
    private readonly server: http.Server,
    public readonly url: string
  ) {}

  static async start(): Promise<LocalHttpServer> {
    let instance: LocalHttpServer | undefined;
    const server = http.createServer((req, res) => {
      if (instance) instance.inFlight += 1;
      res.on('close', () => {
        if (instance) instance.inFlight -= 1;
      });
      const chunks: Buffer[] = [];
      req.on('data', (chunk: Buffer) => chunks.push(chunk));
      res.on('close', () => {
        if (!res.writableFinished && instance) instance.abortedRequests += 1;
      });
      req.on('end', async () => {
        const recorded: RecordedRequest = {
          path: req.url ?? '',
          method: req.method ?? '',
          body: Buffer.concat(chunks).toString('utf-8'),
          headers: Object.fromEntries(
            Object.entries(req.headers).map(([name, value]) => [name, Array.isArray(value) ? value.join(', ') : (value ?? '')])
          ),
        };
        instance?.recorded.push(recorded);
        const configured = instance?.reply ?? { status: 500 };
        const reply = typeof configured === 'function' ? await configured(recorded) : configured;
        if (reply.hang) {
          return;
        }
        if (reply.brokenBody) {
          res.writeHead(reply.status, { 'content-length': '1000' });
          res.write('x', () => res.socket?.destroy());
          return;
        }
        res.writeHead(reply.status, { 'content-type': 'application/json', ...(reply.headers ?? {}) });
        if (reply.bodyGate) {
          res.flushHeaders();
          await reply.bodyGate;
        }
        res.end(reply.bodyBytes ?? reply.body ?? '');
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const { port } = server.address() as AddressInfo;
    instance = new LocalHttpServer(server, `http://127.0.0.1:${port}`);
    return instance;
  }

  /** 固定の応答、または受信した要求から応答を決める関数を設定する。 */
  respondWith(reply: ReplyOrHandler): void {
    this.reply = reply;
  }

  /** 処理中の要求が無くなるまで待つ(上限 timeoutMs。応答を返さない要求を待ち続けないため)。 */
  async whenIdle(timeoutMs = 1000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (this.inFlight > 0 && Date.now() < deadline) {
      await new Promise((resolve) => setImmediate(resolve));
    }
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

/** JSON を本文にした応答。payload が undefined なら本文は空。 */
export function jsonReply(payload: unknown, status = 200, headers: Record<string, string> = {}): Reply {
  return { status, body: payload === undefined ? '' : JSON.stringify(payload), headers };
}

/**
 * `METHOD /path`(クエリを除く完全一致)から応答を引く応答関数。表に無い要求は 404。
 * 値は固定の応答か、受信した要求から応答を決める関数。
 */
export function routes(
  table: Record<string, Reply | ((request: RecordedRequest) => Reply | Promise<Reply>)>
): (request: RecordedRequest) => Reply | Promise<Reply> {
  return (request) => {
    const key = `${request.method} ${request.path.split('?')[0]}`;
    const entry = table[key];
    if (entry === undefined) {
      return { status: 404, body: JSON.stringify({ error: `no route: ${key}` }) };
    }
    return typeof entry === 'function' ? entry(request) : entry;
  };
}
