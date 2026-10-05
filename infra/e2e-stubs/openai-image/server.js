'use strict';
/**
 * OpenAI 画像生成API(gpt-image-1)のスタブ(issue #928 / AT-2)。
 *
 * media-service の ChatGptImageClient は POST {baseUrl}/images/generations を叩き、
 * 応答の data[].b64_json を Base64 デコードして PNG として保存する。
 * したがってスタブは**実際にデコードできるPNG**を返す必要がある。
 *
 * 決定性: 返すPNGは固定バイト列(1x1・RGBA(0, 255, 0, 127) = 半透明の緑)。プロンプトやサイズで内容は変えない。
 * 画像の中身を検証したいシナリオは無く、検証したいのは「保存され、ギャラリーに現れ、
 * 記事へ添付できるか」なので、これで足りる。
 *
 * `n`(batchSize)は尊重する。複数枚生成のシナリオが枚数をアサートできるようにするため。
 *
 * 参照画像付き(issue #1602)の POST {baseUrl}/images/edits は multipart/form-data で
 * image(ファイル)・prompt・model・n・size を受け取る。何を受け取ったかは `/__control/state` の
 * `edits` に(generations は `generations` に)記録するので、受け入れテストはプロンプトの印で
 * 自分の要求を特定して、画像が添付されたか・指示・モデルを検査できる。応答は generations と
 * 同じ固定PNGで、応答本文に受信内容は混ぜない(決定性)。
 */
const { createStub } = require('../lib/stub');

/**
 * 1x1 の固定PNG(8bit RGBA、画素は RGBA(0, 255, 0, 127))。zlib 圧縮済みの最小PNGをそのまま持つ。
 * 生成のたびに作らないのは、エンコーダのバージョン差でバイト列が変わり
 * 決定性が崩れるのを避けるため。
 */
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

/** 受け入れテストが検査するための受信記録(制御エンドポイントからだけ見える)。 */
const edits = [];
const generations = [];

/**
 * multipart/form-data を、フィールド名ごとの { value, filename, contentType, bytes } へ分解する。
 * 画像はバイナリだがここでは「添付されたか・型・大きさ」だけ見られれば足りるので、
 * 文字列のまま区切って扱う(bytes は目安)。
 */
function parseMultipart(contentType, raw) {
  const match = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || '');
  const fields = {};
  if (!match) return fields;
  const boundary = `--${match[1] || match[2]}`;
  for (const part of raw.split(boundary)) {
    const headerEnd = part.indexOf('\r\n\r\n');
    if (headerEnd < 0) continue;
    const head = part.slice(0, headerEnd);
    const name = /name="([^"]*)"/i.exec(head);
    if (!name) continue;
    const content = part.slice(headerEnd + 4).replace(/\r\n$/, '');
    const filename = /filename="([^"]*)"/i.exec(head);
    const type = /content-type:\s*([^\r\n]+)/i.exec(head);
    fields[name[1]] = {
      value: content,
      filename: filename ? filename[1] : null,
      contentType: type ? type[1].trim() : null,
      bytes: Buffer.byteLength(content, 'utf8'),
    };
  }
  return fields;
}

function respondWithImages(res, sendJson, n) {
  sendJson(res, 200, {
    created: 1_756_684_800,
    data: Array.from({ length: n }, () => ({ b64_json: PNG_BASE64 })),
  });
}

createStub({
  name: 'openai-image',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({
    error: { message: `[${name}] forced ${status}`, type: 'stub_error', code: String(status) },
  }),
  onReset() {
    edits.length = 0;
    generations.length = 0;
  },
  extraState: () => ({ edits, generations }),
  async handle({ req, method, pathname, body, res, sendJson }) {
    // baseUrl に /v1 を含める設定(既定は https://api.openai.com/v1)と含めない設定の両方を受ける。
    if (method === 'POST' && /\/(v1\/)?images\/edits$/.test(pathname)) {
      const fields = parseMultipart(req.headers['content-type'], body || '');
      const image = fields.image;
      edits.push({
        prompt: fields.prompt ? fields.prompt.value : null,
        model: fields.model ? fields.model.value : null,
        image: image ? { filename: image.filename, contentType: image.contentType, bytes: image.bytes } : null,
      });
      const n = Math.max(1, Math.min(10, Number(fields.n && fields.n.value) || 1));
      respondWithImages(res, sendJson, n);
      return true;
    }
    if (method !== 'POST' || !/\/(v1\/)?images\/generations$/.test(pathname)) return false;

    let n = 1;
    let prompt = null;
    try {
      const parsed = JSON.parse(body || '{}');
      n = Math.max(1, Math.min(10, Number(parsed.n) || 1));
      prompt = typeof parsed.prompt === 'string' ? parsed.prompt : null;
    } catch {
      // 解析できないボディでも1枚返す。
    }
    generations.push({ prompt });

    respondWithImages(res, sendJson, n);
    return true;
  },
});
