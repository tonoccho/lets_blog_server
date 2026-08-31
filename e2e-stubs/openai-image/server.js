'use strict';
/**
 * OpenAI 画像生成API(gpt-image-1)のスタブ(issue #928 / AT-2)。
 *
 * media-service の ChatGptImageClient は POST {baseUrl}/images/generations を叩き、
 * 応答の data[].b64_json を Base64 デコードして PNG として保存する。
 * したがってスタブは**実際にデコードできるPNG**を返す必要がある。
 *
 * 決定性: 返すPNGは固定バイト列(1x1の不透明な青)。プロンプトやサイズで内容は変えない。
 * 画像の中身を検証したいシナリオは無く、検証したいのは「保存され、ギャラリーに現れ、
 * 記事へ添付できるか」なので、これで足りる。
 *
 * `n`(batchSize)は尊重する。複数枚生成のシナリオが枚数をアサートできるようにするため。
 */
const { createStub } = require('../lib/stub');

/**
 * 1x1 の青いPNG(固定)。zlib 圧縮済みの最小PNGをそのまま持つ。
 * 生成のたびに作らないのは、エンコーダのバージョン差でバイト列が変わり
 * 決定性が崩れるのを避けるため。
 */
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

createStub({
  name: 'openai-image',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({
    error: { message: `[${name}] forced ${status}`, type: 'stub_error', code: String(status) },
  }),
  async handle({ method, pathname, body, res, sendJson }) {
    // baseUrl に /v1 を含める設定(既定は https://api.openai.com/v1)と含めない設定の両方を受ける。
    if (method !== 'POST' || !/\/(v1\/)?images\/generations$/.test(pathname)) return false;

    let n = 1;
    try {
      const parsed = JSON.parse(body || '{}');
      n = Math.max(1, Math.min(10, Number(parsed.n) || 1));
    } catch {
      // 解析できないボディでも1枚返す。
    }

    sendJson(res, 200, {
      created: 1_756_684_800,
      data: Array.from({ length: n }, () => ({ b64_json: PNG_BASE64 })),
    });
    return true;
  },
});
