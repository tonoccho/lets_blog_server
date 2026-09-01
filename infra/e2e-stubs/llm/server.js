'use strict';
/**
 * 外部LLM(OpenAI互換 Chat Completions)のスタブ(issue #843 で新設、#928 で infra/e2e-stubs/ へ移設)。
 *
 * ai-service の LlmClient は {baseUrl}/chat/completions を叩く。生成品質ではなく
 * 「生成結果がUI・DB・公開先へどう反映されるか」を検証するので、決定的な応答で足りる。
 *
 * 応答はプロンプトの内容で分岐する。呼び元がどの用途で呼んだかを埋め込みで判別し、
 * その用途が期待する形式(カスタムタグならHTML/CSSのフェンス)を返す。
 * 用途が判別できない場合は汎用の文章を返す。
 *
 * 決定性のため created は固定値。`Date.now()` を入れると同一入力でも応答が変わり、
 * シナリオが応答全体をアサートできなくなる。
 */
const { createStub } = require('../lib/stub');

/** 固定のUnix秒。決定性のために Date.now() を使わない。 */
const FIXED_CREATED = 1_756_684_800;

/** カスタムタグ生成が抽出できる形式。CustomTagGenerationService の HTML_PATTERN/CSS_PATTERN に合わせる。 */
const CUSTOM_TAG_COMPLETION = [
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

const DRAFT_COMPLETION = [
  '# E2Eスタブの下書き',
  '',
  'これは受け入れテスト用の決定的な下書きです。',
  '',
  '## 背景',
  '',
  'スタブが返す固定の本文なので、シナリオはこの文字列をそのままアサートできます。',
  '',
  '## まとめ',
  '',
  '外部LLMに依存せずに、生成結果の反映先だけを検証します。',
].join('\n');

const TAGS_COMPLETION = 'e2e-stub-tag-a, e2e-stub-tag-b, e2e-stub-tag-c';

/**
 * タグ提案(AiAssistService#suggestTags)はプロンプトでJSONオブジェクトを要求し、
 * 応答をJSONとして解釈する。解釈できないと候補が空になり、呼び元は「候補が返らない」ことしか
 * 観測できない。そのためJSONを要求されたときはJSONで返す(issue #942)。
 */
const TAGS_JSON_COMPLETION = JSON.stringify({
  categories: ['E2Eスタブ分類'],
  tags: ['e2e-stub-tag-a', 'e2e-stub-tag-b', 'e2e-stub-tag-c'],
});

/**
 * 校正チェック(AiAssistService#proofread)はJSON配列の指摘一覧を要求する。
 * originalText は「本文中の該当箇所をそのまま引用したもの」でなければ、呼び元(拡張の
 * 波線表示)が位置を特定できない。プロンプト末尾の本文から先頭行を取り出して使うことで、
 * 任意の入力に対して決定的かつ本文に実在する指摘を返す(issue #942)。
 */
function proofreadIssuesJson(prompt) {
  const bodyIndex = prompt.lastIndexOf('本文:');
  const body = bodyIndex >= 0 ? prompt.slice(bodyIndex + '本文:'.length) : prompt;
  const firstLine = body.split('\n').map((line) => line.trim()).find((line) => line !== '') ?? '';
  if (firstLine === '') {
    return '[]';
  }
  return JSON.stringify([
    {
      type: 'typo',
      originalText: firstLine,
      message: '[E2Eスタブ] 誤字脱字の可能性があります。',
      suggestion: null,
    },
  ]);
}

const PROOFREAD_COMPLETION = [
  '校正結果(E2Eスタブ):',
  '',
  '- 1行目: 「てにをは」の誤りがあります。',
  '- 3行目: 冗長な表現です。',
].join('\n');

const IMAGE_PROMPT_COMPLETION =
  'a deterministic e2e stub illustration, flat vector style, blue and white';

const GENERIC_COMPLETION = 'E2Eスタブの応答です。';

/**
 * プロンプトから用途を推定する。ai-service 側のプロンプト文言に依存しすぎないよう、
 * 判定はゆるく、既定は汎用応答にしている。
 */
function completionFor(prompt) {
  const p = prompt.toLowerCase();
  // JSONを要求するプロンプト(タグ提案・校正チェック)は、要求された形式で返す。
  // 判定にはプロンプトが提示する出力例そのものを使い、文言の言い回しに依存させない。
  if (prompt.includes('{"categories":')) {
    return TAGS_JSON_COMPLETION;
  }
  if (prompt.includes('{"type": "typo"')) {
    return proofreadIssuesJson(prompt);
  }
  if (prompt.includes('カスタムタグ') || p.includes('custom tag') || p.includes('```css')) {
    return CUSTOM_TAG_COMPLETION;
  }
  if (prompt.includes('画像') && (prompt.includes('プロンプト') || p.includes('prompt'))) {
    return IMAGE_PROMPT_COMPLETION;
  }
  if (prompt.includes('校正') || p.includes('proofread')) return PROOFREAD_COMPLETION;
  if (prompt.includes('タグ') || p.includes('tags')) return TAGS_COMPLETION;
  if (prompt.includes('下書き') || prompt.includes('記事') || p.includes('draft')) return DRAFT_COMPLETION;
  return GENERIC_COMPLETION;
}

createStub({
  name: 'llm',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({
    error: { message: `[${name}] forced ${status}`, type: 'stub_error', code: String(status) },
  }),
  async handle({ method, pathname, body, res, sendJson }) {
    // OpenAI互換クライアントは baseUrl の末尾に /v1 を含める流儀もあるため、両方を受ける。
    if (method !== 'POST' || !/\/(v1\/)?chat\/completions$/.test(pathname)) return false;

    let prompt = '';
    try {
      const parsed = JSON.parse(body || '{}');
      prompt = (parsed.messages || []).map((m) => m.content || '').join('\n');
    } catch {
      // 解析できないボディでも応答は返す(呼び元の形式差で落とさない)。
    }

    // 入力による失敗誘発。制御エンドポイントを使えない経路(既存 spec)との互換のために残す。
    if (prompt.includes('FORCE_LLM_ERROR')) {
      sendJson(res, 500, { error: { message: 'forced error for E2E (FORCE_LLM_ERROR)' } });
      return true;
    }

    sendJson(res, 200, {
      id: 'chatcmpl-e2e-stub',
      object: 'chat.completion',
      created: FIXED_CREATED,
      model: 'e2e-stub',
      choices: [
        { index: 0, message: { role: 'assistant', content: completionFor(prompt) }, finish_reason: 'stop' },
      ],
      usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
    });
    return true;
  },
});
