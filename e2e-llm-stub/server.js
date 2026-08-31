'use strict';
/**
 * E2E用のLLMスタブ(issue #843)。
 *
 * OpenAI互換の Chat Completions(POST /chat/completions)だけを実装し、
 * カスタムタグ生成が期待する形式(```html / ```css のフェンス)で決定的な応答を返す。
 *
 * なぜスタブか: custom-tag-generation.spec.ts の「正常系: 生成から自動保存までの完全フロー」は、
 * .env の LLM_API_KEY がプレースホルダのままだと生成が必ず失敗し、test.skip に落ちて
 * 恒常的に未検証だった。この spec が検証したいのは「生成結果がUIとDBにどう反映されるか」であって
 * LLMの生成品質ではないため、実キー不要で決定的なスタブで目的を果たせる。
 *
 * 生成内容を固定できるので、「編集モードで開く」「再読み込み後も一覧に残る」の検証も安定する。
 *
 * 失敗系も検証できるよう、プロンプトに FORCE_LLM_ERROR が含まれる場合は 500 を返す
 * (spec のエラー表示の分岐を残すため)。
 */
const http = require('node:http');

const PORT = Number(process.env.PORT || 8080);

/** カスタムタグ生成が抽出できる形式。CustomTagGenerationService の HTML_PATTERN/CSS_PATTERN に合わせる。 */
function completionText() {
  return [
    'E2Eスタブによる生成結果です。',
    '',
    '```html',
    '<div class="e2e-stub-tag">',
    '  <span class="e2e-stub-tag__label">E2E Stub</span>',
    '</div>',
    '```',
    '',
    '```css',
    '.e2e-stub-tag {',
    '  display: inline-block;',
    '  padding: 4px 8px;',
    '}',
    '```',
    '',
  ].join('\n');
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok\n');
    return;
  }

  if (req.method !== 'POST' || !req.url.startsWith('/chat/completions')) {
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: { message: `not implemented: ${req.method} ${req.url}` } }));
    return;
  }

  let raw = '';
  req.on('data', (chunk) => {
    raw += chunk;
    // 想定外に大きなボディでメモリを食わないよう上限を設ける。
    if (raw.length > 1_000_000) {
      req.destroy();
    }
  });
  req.on('end', () => {
    let prompt = '';
    try {
      const body = JSON.parse(raw || '{}');
      prompt = (body.messages || []).map((m) => m.content || '').join('\n');
    } catch {
      // 解析できないボディでも生成は返す(spec はプロンプト内容に依存しない)。
    }

    // 失敗系の検証用。spec 側から明示的にエラーを起こせるようにしておく。
    if (prompt.includes('FORCE_LLM_ERROR')) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'forced error for E2E' } }));
      return;
    }

    const payload = {
      id: 'chatcmpl-e2e-stub',
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model: 'e2e-stub',
      choices: [
        { index: 0, message: { role: 'assistant', content: completionText() }, finish_reason: 'stop' },
      ],
      usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
    };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(payload));
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[e2e-llm-stub] listening on ${PORT}`);
});
