'use strict';
/**
 * Brave Search API のスタブ(issue #928 / AT-2)。
 *
 * ai-service の BraveSearchClient は GET {baseUrl}/res/v1/web/search?q=&count= を叩き、
 * X-Subscription-Token ヘッダでAPIキーを送る。応答からは web.results[].{title,description,url}
 * だけを読む。
 *
 * 決定性: クエリ文字列を結果のタイトルに埋め込む。同じクエリなら常に同じ結果になり、
 * 「検索結果が回答の根拠に使われたか」をシナリオがアサートできる。
 *
 * APIキー検証: `e2e-stub-invalid-key` を送ると401を返す。「キーが無効なとき利用者に何が見えるか」を
 * 制御エンドポイント無しで検証できる。キー未設定の場合は BraveSearchClient が呼ぶ前に弾く。
 */
const { createStub } = require('../lib/stub');

const RESULT_COUNT_DEFAULT = 3;

createStub({
  name: 'brave-search',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({
    type: 'ErrorResponse',
    error: { id: 'stub', status, code: 'FORCED', detail: `[${name}] forced ${status}` },
  }),
  async handle({ method, pathname, query, req, res, sendJson }) {
    if (method !== 'GET' || pathname !== '/res/v1/web/search') return false;

    if (req.headers['x-subscription-token'] === 'e2e-stub-invalid-key') {
      sendJson(res, 401, {
        type: 'ErrorResponse',
        error: { id: 'stub', status: 401, code: 'SUBSCRIPTION_TOKEN_INVALID',
                 detail: '[stub] Brave Search APIキーが不正です' },
      });
      return true;
    }

    const q = query.get('q') ?? '';
    const count = Math.max(1, Math.min(20, Number(query.get('count') ?? RESULT_COUNT_DEFAULT) || RESULT_COUNT_DEFAULT));
    const results = Array.from({ length: count }, (_, i) => ({
      title: `[E2Eスタブ] ${q} の検索結果 ${i + 1}`,
      description: `「${q}」に対する決定的なスタブ応答の ${i + 1} 件目です。`,
      url: `https://e2e-stub.invalid/search/${i + 1}`,
      profile: { name: 'e2e-stub', url: 'https://e2e-stub.invalid' },
    }));

    sendJson(res, 200, {
      type: 'search',
      query: { original: q },
      web: { type: 'search', results },
    });
    return true;
  },
});
