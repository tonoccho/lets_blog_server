import type { AxiosRequestConfig } from 'axios';

/**
 * axiosのヘッダー型をfetchの`HeadersInit`へ落とし込む(issue #810)。
 *
 * <p>`AxiosRequestConfig['headers']`の値は`string | number | boolean | null | undefined`を
 * 取りうるが、`fetch`のヘッダーは文字列しか受け付けない。そのまま展開すると
 * `Type 'null' is not assignable to type 'string'` になる。
 * この型エラーは、#810でsdk/api-client自体の型チェックを入れて初めて検出された
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

export const apiClient = async <T>(
  config: AxiosRequestConfig,
  options?: any
): Promise<T> => {
  const baseUrl = process.env.REACT_APP_API_URL || process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
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
