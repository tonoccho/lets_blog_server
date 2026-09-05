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

const PROOFREAD_COMPLETION = [
  '校正結果(E2Eスタブ):',
  '',
  '- 1行目: 「てにをは」の誤りがあります。',
  '- 3行目: 冗長な表現です。',
].join('\n');

const IMAGE_PROMPT_COMPLETION =
  'a deterministic e2e stub illustration, flat vector style, blue and white';

const GENERIC_COMPLETION = 'E2Eスタブの応答です。';

// ------------------------------------ タグ提案・校正チェック(issue #1004 / AT-8・AT-16)

/**
 * JSON出力を要求するプロンプトの判定に使う、**プロンプトが載せている出力例そのもの**。
 *
 * 「タグ」「校正」のような言い回しで判定すると、同じ語を含む別用途
 * (`/api/ai/draft` の mode=proofread は散文の全文校正を求める)まで巻き込む。
 * 出力例は「JSONで返せ」という要求そのものなので、取り違えようがない。
 *
 * 出典は ai-service の AiAssistService:
 *   TAGS_PROMPT_TEMPLATE            → {"categories": ["カテゴリ1", ...], "tags": [...]}
 *   PROOFREAD_CHECK_PROMPT_TEMPLATE → [{"type": "typo", "originalText": ..., ...}]
 */
const TAGS_JSON_MARKER = '{"categories":';
const PROOFREAD_JSON_MARKER = '{"type": "typo"';

/** タグ提案の候補。タグ名は散文版({@link TAGS_COMPLETION})と同じにしてある。 */
const TAGS_JSON_CATEGORIES = ['E2Eスタブのカテゴリ'];
const TAGS_JSON_TAGS = ['e2e-stub-tag-a', 'e2e-stub-tag-b', 'e2e-stub-tag-c'];

const TAGS_JSON_COMPLETION = JSON.stringify({
  categories: TAGS_JSON_CATEGORIES,
  tags: TAGS_JSON_TAGS,
});

/**
 * 校正の指摘。引用({@code originalText})だけは本文から作るので、ここには持たない。
 * 種別は AiAssistService が挙げる typo / readability / unnecessary から2つを使う。
 * suggestion が null になる指摘(readability)を必ず1件含め、
 * 「置き換え案の無い指摘」の経路も呼び元が観測できるようにする。
 */
const PROOFREAD_ISSUE_KINDS = [
  {
    type: 'typo',
    message: 'E2Eスタブが検出した誤字脱字の指摘です。',
    suggestion: (quote) => `${quote}(E2Eスタブの置き換え案)`,
  },
  {
    type: 'readability',
    message: 'E2Eスタブが検出した読みにくい表現の指摘です。',
    suggestion: () => null,
  },
];

/** 引用の長さ(コードポイント数)。本文が短くても収まるよう控えめにする。 */
const PROOFREAD_QUOTE_LENGTH = 8;

/**
 * プロンプト末尾に積まれた本文を取り出す。ai-service のテンプレートは
 * どちらも「(空行)本文:(改行)」に続けて本文を差し込む。
 */
function promptBodyText(prompt) {
  const marker = '\n本文:\n';
  const at = prompt.indexOf(marker);
  return at < 0 ? '' : prompt.slice(at + marker.length);
}

/**
 * 本文から引用を決定的に切り出す。
 *
 * AiAssistService#parseProofreadResponse は originalText が本文中に**実在しない**指摘を
 * 捨てる(エディタが波線を引く位置を特定できないため)。したがって引用は本文の
 * 部分文字列でなければならない。文の区切りで割った断片の先頭を使い、
 * サロゲートペアを割らないようコードポイント単位で切る。
 */
function proofreadQuotes(body) {
  const segments = body
    .split(/[\n。、!?!?]/)
    .map((segment) => segment.trim())
    .filter((segment) => segment !== '');
  const quotes = [];
  for (const segment of segments) {
    const quote = Array.from(segment).slice(0, PROOFREAD_QUOTE_LENGTH).join('');
    if (!quotes.includes(quote)) {
      quotes.push(quote);
    }
    if (quotes.length === PROOFREAD_ISSUE_KINDS.length) {
      break;
    }
  }
  return quotes;
}

function proofreadJsonCompletion(prompt) {
  const issues = proofreadQuotes(promptBodyText(prompt)).map((quote, index) => {
    const kind = PROOFREAD_ISSUE_KINDS[index];
    return {
      type: kind.type,
      originalText: quote,
      message: kind.message,
      suggestion: kind.suggestion(quote),
    };
  });
  return JSON.stringify(issues);
}

/**
 * JSON形式を要求するプロンプトへの応答。判別できなければ null を返し、呼び元へ委ねる。
 *
 * 記事プラン({@link articlePlanCompletionFor})と同じく、**一般判定より先に**通す必要がある。
 * タグ提案のプロンプトは「タグ」を、校正チェックのプロンプトは「校正」を含むため、
 * 後ろに置くと必ず散文({@link TAGS_COMPLETION} / {@link PROOFREAD_COMPLETION})へ吸われ、
 * 呼び元のJSON解釈が失敗して候補も指摘も空になる(#1004 の症状そのもの)。
 */
function jsonFormatCompletionFor(prompt) {
  if (prompt.includes(TAGS_JSON_MARKER)) return TAGS_JSON_COMPLETION;
  if (prompt.includes(PROOFREAD_JSON_MARKER)) return proofreadJsonCompletion(prompt);
  return null;
}

// ------------------------------------------------------- 記事プラン(issue #935 / AT-9)

/**
 * `ArticlePlanService` の SYSTEM_PROMPT に含まれる語。記事プランの用途を他と見分ける。
 * プロンプト全文ではなく特徴語で判定するのは、文言が多少変わっても壊れないようにするため。
 */
const PLAN_SYSTEM_MARKER = 'ブログ記事企画の壁打ち相手';

/** 壁打ちセッションの見出し。`generateSessionTitle` はこの文字列をそのまま表題にする。 */
const PLAN_SESSION_TITLE = 'E2Eスタブの企画メモ';

/** `suggest-titles` が JSON 配列として解釈する。件数(5)はシナリオがそのまま数える。 */
const PLAN_TITLES = [
  'E2Eスタブのタイトル案1',
  'E2Eスタブのタイトル案2',
  'E2Eスタブのタイトル案3',
  'E2Eスタブのタイトル案4',
  'E2Eスタブのタイトル案5',
];

const PLAN_SLUGS = [
  'e2e-stub-plan-1',
  'e2e-stub-plan-2',
  'e2e-stub-plan-3',
  'e2e-stub-plan-4',
  'e2e-stub-plan-5',
];

const PLAN_TAGS = ['e2e-stub-plan-tag-a', 'e2e-stub-plan-tag-b', 'e2e-stub-plan-tag-c'];

/** 構成案。見出しが階層(##/###)になっていることをシナリオが確かめる。 */
const PLAN_STRUCTURE_COMPLETION = [
  '## E2Eスタブの構成案',
  '',
  '### 背景',
  '',
  '### 具体的な手順',
  '',
  '### まとめ',
].join('\n');

/**
 * 公開先に存在しないカテゴリ名。メタデータ提案にわざと混ぜる。
 * `ArticlePlanService#filterToExistingCategories` が既存カテゴリだけへ絞り込むことを、
 * プロンプトの指示任せではなく応答の中身で確かめられるようにするため。
 */
const PLAN_UNKNOWN_CATEGORY = 'E2Eスタブの実在しないカテゴリ';

/**
 * プロンプトに積まれた利用者の発言の数。壁打ちの応答へ埋め込むことで、
 * 「直前までの文脈がLLMへ渡っているか」をシナリオが観測できるようにする。
 * 同じ入力なら同じ数になるので決定性は崩れない。
 */
function planUserTurnCount(prompt) {
  return prompt.split('\n').filter((line) => line.startsWith('User: ')).length;
}

function planChatCompletion(prompt) {
  return `E2Eスタブの壁打ち応答です(あなたの発言 ${planUserTurnCount(prompt)} 件目)。`
    + 'テーマをもう少し具体的に教えてください。';
}

/**
 * メタデータ提案のプロンプトに埋め込まれた「既存カテゴリ一覧」を取り出す。
 * `ArticlePlanService#buildMetadataSuggestionPrompt` が
 * 「…の中からこの記事に合うものだけを選んでください(…): A, B」の形で並べる。
 */
function planExistingCategories(prompt) {
  const match = /既存カテゴリ一覧の中から[\s\S]*?: (.+)/.exec(prompt);
  if (!match) {
    return [];
  }
  return match[1].split(',').map((name) => name.trim()).filter((name) => name !== '');
}

function planMetadataCompletion(prompt) {
  return JSON.stringify(
    {
      titles: PLAN_TITLES,
      slugs: PLAN_SLUGS,
      categories: [...planExistingCategories(prompt), PLAN_UNKNOWN_CATEGORY],
      tags: PLAN_TAGS,
    },
    null,
    2
  );
}

/**
 * 記事プラン用の応答。用途が判別できなければ null を返し、呼び元の一般判定へ委ねる。
 *
 * 一般判定より**先に**通す必要がある。例えばメタデータ提案のプロンプトは「タグ」も
 * 「記事」も含むため、後ろに置くと `TAGS_COMPLETION` に吸われて JSON にならない。
 */
function articlePlanCompletionFor(prompt) {
  if (prompt.includes('短い見出しに要約')) return PLAN_SESSION_TITLE;
  if (prompt.includes('記事タイトル案をJSON配列形式で')) return JSON.stringify(PLAN_TITLES);
  if (prompt.includes('構成案をMarkdown形式の見出し構造')) return PLAN_STRUCTURE_COMPLETION;
  if (prompt.includes('記事の以下の情報をJSON形式で提案してください')) return planMetadataCompletion(prompt);
  if (prompt.includes(PLAN_SYSTEM_MARKER)) return planChatCompletion(prompt);
  return null;
}

/**
 * プロンプトから用途を推定する。ai-service 側のプロンプト文言に依存しすぎないよう、
 * 判定はゆるく、既定は汎用応答にしている。
 */
function completionFor(prompt) {
  const p = prompt.toLowerCase();
  const planCompletion = articlePlanCompletionFor(prompt);
  if (planCompletion !== null) {
    return planCompletion;
  }
  const jsonCompletion = jsonFormatCompletionFor(prompt);
  if (jsonCompletion !== null) {
    return jsonCompletion;
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
