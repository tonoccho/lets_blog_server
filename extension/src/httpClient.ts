import * as http from 'http';
import * as https from 'https';
import { URL } from 'url';

/** レスポンスのうち、このクライアントが必要とする最小の形。 */
export interface HttpResponse {
  status: number;
  ok: boolean;
  statusText: string;
  /** 応答本文を文字列として読む。 */
  text(): Promise<string>;
  /** 応答本文をJSONとして読む(検証はrequestJson側で行う)。 */
  json(): Promise<unknown>;
  /** 応答本文をバイナリとして読む(画像の取得に使う)。 */
  arrayBuffer(): Promise<ArrayBuffer>;
}

/** httpRequestへ渡すリクエストの内容。 */
export interface HttpRequestOptions {
  method: string;
  headers: Record<string, string>;
  body?: string | Buffer;
  signal: AbortSignal;
  /**
   * 自己署名証明書を許容するか。trueの場合のみhttpsモジュール経由のトランスポートを使う
   * (ネイティブfetchはリクエスト単位で証明書検証を緩める手段を持たないため)。
   */
  allowInsecureTls: boolean;
}

/**
 * HTTPリクエストを実行する。
 *
 * 既定ではNode 18以降のネイティブfetchを使う(node-fetchへの依存を持たないため)。
 * ただしネイティブfetchはリクエスト単位でTLS検証を無効化できないため、
 * 自己署名証明書を許容する設定(letsBlog.allowInsecureTls)が有効なHTTPS接続に限り、
 * rejectUnauthorized=falseを指定できるnode:httpsのトランスポートへ切り替える。
 */
export async function httpRequest(url: string, options: HttpRequestOptions): Promise<HttpResponse> {
  const useInsecureTransport = options.allowInsecureTls && url.startsWith('https://');
  return useInsecureTransport ? requestViaNodeHttps(url, options) : requestViaFetch(url, options);
}

async function requestViaFetch(url: string, options: HttpRequestOptions): Promise<HttpResponse> {
  const res = await fetch(url, {
    method: options.method,
    headers: options.headers,
    body: options.body,
    signal: options.signal,
  });
  return {
    status: res.status,
    ok: res.ok,
    statusText: res.statusText,
    text: () => res.text(),
    json: () => res.json(),
    arrayBuffer: () => res.arrayBuffer(),
  };
}

/** 自己署名証明書を許容する場合のトランスポート。応答本文はBufferへ集めてから返す。 */
function requestViaNodeHttps(url: string, options: HttpRequestOptions): Promise<HttpResponse> {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const transport = parsed.protocol === 'http:' ? http : https;

    const req = transport.request(
      {
        protocol: parsed.protocol,
        hostname: parsed.hostname,
        port: parsed.port || undefined,
        path: `${parsed.pathname}${parsed.search}`,
        method: options.method,
        headers: options.headers,
        rejectUnauthorized: false,
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (chunk: Buffer) => chunks.push(chunk));
        res.on('end', () => {
          const buffer = Buffer.concat(chunks);
          const status = res.statusCode ?? 0;
          resolve({
            status,
            ok: status >= 200 && status < 300,
            statusText: res.statusMessage ?? '',
            text: async () => buffer.toString('utf-8'),
            json: async () => JSON.parse(buffer.toString('utf-8')),
            arrayBuffer: async () =>
              buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength) as ArrayBuffer,
          });
        });
        res.on('error', reject);
      }
    );

    // AbortSignalはhttpsモジュールでは自動で扱われないため、明示的に中断へつなぐ。
    const onAbort = (): void => {
      req.destroy(abortError());
    };
    if (options.signal.aborted) {
      onAbort();
    } else {
      options.signal.addEventListener('abort', onAbort, { once: true });
    }
    req.on('close', () => options.signal.removeEventListener('abort', onAbort));

    req.on('error', reject);
    if (options.body) {
      req.write(options.body);
    }
    req.end();
  });
}

/** ネイティブfetchの中断時と同じ名前の例外を投げ、呼び出し側の中断判定を共通化する。 */
function abortError(): Error {
  const error = new Error('The operation was aborted');
  error.name = 'AbortError';
  return error;
}
