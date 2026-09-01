import type { AxiosRequestConfig } from 'axios';

/**
 * axiosのヘッダー型をfetchの`HeadersInit`へ落とし込む(issue #810)。
 *
 * <p>`AxiosRequestConfig['headers']`の値は`string | number | boolean | null | undefined`を
 * 取りうるが、`fetch`のヘッダーは文字列しか受け付けない。そのまま展開すると
 * `Type 'null' is not assignable to type 'string'` になる。
 * この型エラーは、#810でpackages/api-client自体の型チェックを入れて初めて検出された
 * (index.tsから再エクスポートされていないため、webの型チェックからは到達しなかった)。
 *
 * <p>null/undefinedのヘッダーは「指定なし」として落とす(空文字を送ると、
 * サーバー側で「空の値が明示された」と解釈されうるため)。
 */
function toFetchHeaders(headers: AxiosRequestConfig['headers']): Record<string, string> {
  if (!headers) {
    return {};
  }
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(headers)) {
    if (value !== null && value !== undefined) {
      result[key] = String(value);
    }
  }
  return result;
}

/**
 * orval の custom mutator として使うことを想定した最小のフェッチ実装。
 *
 * <p><b>現在このファイルはどこからも参照されていない</b>(ADR-0009)。
 * `orval.config.js` は mutator を指定しておらず、生成コードは自前で `fetch` を呼ぶ。
 * web は `apps/web/src/lib/apiClient.ts`(server-only、Bearerトークン付与あり)を使う。
 *
 * <p>以前はここで
 * `process.env.REACT_APP_API_URL || process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080'`
 * とベースURLを組み立てていたが、
 *
 * <ul>
 *   <li>`REACT_APP_API_URL` は Create React App の規約で、このリポジトリに定義が無い</li>
 *   <li>`NEXT_PUBLIC_API_URL` も docker-compose.yml・apps/web/.env.local.example のいずれにも無い</li>
 *   <li>結果として常に `http://localhost:8080` になるが、このポートは外部公開されておらず、
 *       コンテナ内から見た `localhost` は呼び出し元コンテナ自身を指すため到達しない</li>
 * </ul>
 *
 * という「もっともらしいが必ず失敗する」既定値だった(issue #750)。
 * web 側のベースURL組み立ては `apps/web/src/lib/apiBaseUrl.ts` の `gatewayUrl()` に集約済みで、
 * ここに独自の解決を残すと二重管理になる。そのため**呼び出し元が明示的に渡す**形にした。
 *
 * @param baseUrl 呼び出し先のベースURL。web から使う場合は `gatewayUrl('')` 相当を渡すこと。
 *   認証が必要なエンドポイントでは、併せて `config.headers` に `Authorization` を載せること
 *   (この関数自体はトークンを解決しない)。
 */
export const apiClient = async <T>(
  config: AxiosRequestConfig,
  baseUrl: string,
  options?: any
): Promise<T> => {
  const url = `${baseUrl}${config.url || ''}`;

  const response = await fetch(url, {
    method: config.method || 'GET',
    headers: {
      'Content-Type': 'application/json',
      ...toFetchHeaders(config.headers),
    },
    body: config.data ? JSON.stringify(config.data) : undefined,
  });

  if (!response.ok) {
    throw new Error(`API Error: ${response.status} ${response.statusText}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const data = await response.json();
  return data as T;
};
