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
const crypto = require('node:crypto');
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

/**
 * タグデザイン生成(issue #1586)がプロンプトへ入れる、要求ごとの目印。
 * `e2e1409d` は受け入れテスト(llmGenerationJob.steps.ts の requestTagDesignGenerationOnScreen)が
 * 付ける接頭辞で、カスタムタグ生成(`e2e1409g` / `e2e1409a`)の目印とは別。接頭辞まで一致したものだけを
 * 拾うので、カスタムタグ生成など目印を使わない呼び元は従来の {@link CUSTOM_TAG_COMPLETION} のまま。
 * スタブの共有状態を書き換えず、プロンプトから目印を読むだけなので、並列のシナリオと奪い合わない。
 */
const TAG_DESIGN_MARKER_PATTERN = /e2e1409d[0-9a-z]+/;

/** 目印入りのCSSを ```css ブロックで返す。TagDesignGenerationService の CSS_PATTERN に合わせる。 */
function tagDesignMarkedCompletion(marker) {
  return [
    'E2Eスタブによるタグデザインの生成結果です。',
    '',
    '```css',
    `.e2e-stub-tag-${marker} {`,
    '  display: inline-block;',
    '  padding: 4px 8px;',
    '}',
    '```',
    '',
  ].join('\n');
}

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
 *
 * media-service の画像タグ提案(issue #281 / #1077)はこの2つとは別の呼び元だが、
 * 出力例が {"tags": [...]} だけの単独オブジェクトで、{@link TAGS_JSON_MARKER}
 * ({"categories": ... を含む複合オブジェクト)とは文字列として重ならない
 * (出典: ImageGenerationService.IMAGE_TAGS_PROMPT_TEMPLATE)。このプロンプトは
 * 「画像」「プロンプト」も含むため、一般判定({@link completionFor}の
 * `prompt.includes('画像') && (プロンプト系)` 分岐)に先に吸われると
 * IMAGE_PROMPT_COMPLETION(英文のSDプロンプト)が返り、JSON解釈に失敗して
 * media-service側のタグ提案が常に空になる(#1077 の症状そのもの)。
 */
const TAGS_JSON_MARKER = '{"categories":';
const PROOFREAD_JSON_MARKER = '{"type": "typo"';
const IMAGE_TAGS_JSON_MARKER = '{"tags": ["タグ1", "タグ2", "タグ3"]}';

/** タグ提案の候補。タグ名は散文版({@link TAGS_COMPLETION})と同じにしてある。 */
const TAGS_JSON_CATEGORIES = ['E2Eスタブのカテゴリ'];
const TAGS_JSON_TAGS = ['e2e-stub-tag-a', 'e2e-stub-tag-b', 'e2e-stub-tag-c'];

const TAGS_JSON_COMPLETION = JSON.stringify({
  categories: TAGS_JSON_CATEGORIES,
  tags: TAGS_JSON_TAGS,
});

/** 画像タグ提案の候補(issue #281 / #1077)。散文版とは独立した専用の値にしてある。 */
const IMAGE_TAGS_JSON_TAGS = ['e2e-stub-image-tag-a', 'e2e-stub-image-tag-b', 'e2e-stub-image-tag-c'];

const IMAGE_TAGS_JSON_COMPLETION = JSON.stringify({ tags: IMAGE_TAGS_JSON_TAGS });

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

// ------------------------------------ レビューステップ単位の指摘生成(issue #1213)

/**
 * ai-service の AiAssistService#REVIEW_STEP_PROMPT_TEMPLATES が積む、出力例そのものではなく
 * ステップ固有の指示文言をマーカーにする({@link PROOFREAD_JSON_MARKER}と同じ考え方)。
 * JAPANESE/PROOFREADING の両テンプレートは出力例の行(`{"originalText": ...`)が同一のため、
 * それでは区別できない。指示文言はそれぞれのステップだけが持つ観点の語であり、
 * 取り違えようがない(出典: ai-service の REVIEW_STEP_PROMPT_TEMPLATES)。
 */
const JAPANESE_STEP_MARKER = 'ら抜き言葉';
const PROOFREADING_STEP_MARKER = '衍字';
/** READER_PERSPECTIVE / STYLE(issue #1221)。各テンプレートだけが持つ観点の語(他ステップのテンプレートには現れない)。 */
const READER_PERSPECTIVE_STEP_MARKER = '前提知識の飛躍';
const STYLE_STEP_MARKER = '文末表現';

const JAPANESE_STEP_MESSAGE = 'E2Eスタブが検出した日本語チェックの指摘です。';
const PROOFREADING_STEP_MESSAGE = 'E2Eスタブが検出した校正チェックの指摘です。';
const READER_PERSPECTIVE_STEP_MESSAGE = 'E2Eスタブが検出した読者視点でのチェックの指摘です。';
const STYLE_STEP_MESSAGE = 'E2Eスタブが検出した文体チェックの指摘です。';

/**
 * 本文の**最後の**文断片を引用として使う(校正チェック用の{@link proofreadQuotes}が先頭からN件
 * 取るのとは逆)。「指摘箇所より前方に文字を挿入しても同じ指摘の識別子が変わらない」ことを
 * 検証するシナリオ(issue #1213)は、本文の先頭に文章を追加してもこの指摘の引用文だけは
 * 変わらないことを前提にしている。先頭から数える方式だと追加した分だけ区切りの数がずれて
 * 別の断片を拾ってしまうが、末尾から数える方式なら追加分が末尾に及ばない限り安定する。
 */
function reviewStepQuote(body) {
  const segments = body
    .split(/[\n。、!?!?]/)
    .map((segment) => segment.trim())
    .filter((segment) => segment !== '');
  if (segments.length === 0) {
    return '';
  }
  const last = segments[segments.length - 1];
  return Array.from(last).slice(0, PROOFREAD_QUOTE_LENGTH).join('');
}

function reviewStepJsonCompletion(prompt, message) {
  const quote = reviewStepQuote(promptBodyText(prompt));
  if (!quote) {
    return JSON.stringify([]);
  }
  return JSON.stringify([{ originalText: quote, message }]);
}

// ------------------------------------ 校閲(FACT_CHECK)ステップ(issue #1214)

/**
 * ai-service の AiAssistService#FACT_CHECK_EXTRACTION_PROMPT_TEMPLATE(1段目: 事実主張の抽出)と
 * FACT_CHECK_JUDGE_PROMPT_TEMPLATE(2段目: 検索結果を踏まえた判定)の、それぞれだけが持つ指示文言を
 * マーカーにする。どちらのプロンプトも「本文:」を末尾に置くため、{@link promptBodyText}で本文を読める。
 * 校閲のプロンプトは「校正」「タグ」等の一般判定の語を含みうるので、{@link jsonFormatCompletionFor}
 * (一般判定より先に通す)で処理する。
 */
const FACT_CHECK_EXTRACTION_MARKER = '事実確認の対象となる主張を抽出';
const FACT_CHECK_JUDGE_MARKER = 'Web検索結果と照らして事実確認';
const FACT_CHECK_MESSAGE = 'E2Eスタブが検出した校閲の指摘です(検索結果から裏付けが取れません)。';

/**
 * 抽出: 本文の**最後の**文断片を主張・検索クエリとして返す({@link reviewStepQuote}と同じ理由で
 * 末尾から取る)。スタブのBrave Searchは検索クエリを結果のタイトルへ埋め込むので、
 * シナリオは「出典が検索結果由来か」を確認できる。
 */
function factCheckExtractionCompletion(prompt) {
  const quote = reviewStepQuote(promptBodyText(prompt));
  if (!quote) {
    return JSON.stringify([]);
  }
  return JSON.stringify([{ claim: quote, query: quote }]);
}

/** 判定: 抽出と同じ引用を、検索結果の1番目を根拠に指摘として返す。 */
function factCheckJudgeCompletion(prompt) {
  const quote = reviewStepQuote(promptBodyText(prompt));
  if (!quote) {
    return JSON.stringify([]);
  }
  return JSON.stringify([{ originalText: quote, message: FACT_CHECK_MESSAGE, sources: [1] }]);
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
  if (prompt.includes(IMAGE_TAGS_JSON_MARKER)) return IMAGE_TAGS_JSON_COMPLETION;
  if (prompt.includes(PROOFREAD_JSON_MARKER)) return proofreadJsonCompletion(prompt);
  if (prompt.includes(FACT_CHECK_EXTRACTION_MARKER)) return factCheckExtractionCompletion(prompt);
  if (prompt.includes(FACT_CHECK_JUDGE_MARKER)) return factCheckJudgeCompletion(prompt);
  if (prompt.includes(JAPANESE_STEP_MARKER)) return reviewStepJsonCompletion(prompt, JAPANESE_STEP_MESSAGE);
  if (prompt.includes(PROOFREADING_STEP_MARKER)) {
    return reviewStepJsonCompletion(prompt, PROOFREADING_STEP_MESSAGE);
  }
  if (prompt.includes(READER_PERSPECTIVE_STEP_MARKER)) {
    return reviewStepJsonCompletion(prompt, READER_PERSPECTIVE_STEP_MESSAGE);
  }
  if (prompt.includes(STYLE_STEP_MARKER)) return reviewStepJsonCompletion(prompt, STYLE_STEP_MESSAGE);
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

/** 壁打ちの Markdown 描画(#1566)を確かめる応答。利用者の**最後の**発言のキーワードで返す。 */
const PLAN_MARKDOWN_REPLY = [
  '## E2Eスタブの見出し',
  '',
  '- 項目A',
  '- 項目B',
  '',
  'これは **E2E強調** のテキストです。',
  '',
  '```',
  'E2E_CODE_LINE = 1',
  '```',
  '',
  '| 列1 | 列2 |',
  '| --- | --- |',
  '| セルa | セルb |',
].join('\n');

const PLAN_HTML_INJECTION_REPLY = [
  '## E2Eスタブの HTML 注入',
  '',
  '<script>window.__e2eXss = 1</script>',
  '',
  '<img src="x" onerror="window.__e2eXss = 2">',
  '',
  '[危険なリンク](javascript:window.__e2eXss=3)',
  '',
  '[安全なリンク](https://example.com/e2e)',
].join('\n');

function planLastUserLine(prompt) {
  const lines = prompt.split('\n').filter((line) => line.startsWith('User: '));
  return lines.length === 0 ? '' : lines[lines.length - 1];
}

function planChatCompletion(prompt) {
  const lastUser = planLastUserLine(prompt);
  if (lastUser.includes('E2E_MARKDOWN_REPLY')) return PLAN_MARKDOWN_REPLY;
  if (lastUser.includes('E2E_HTML_INJECTION_REPLY')) return PLAN_HTML_INJECTION_REPLY;
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

// --------------------------------------------------- セクション生成の壁打ち(issue #1037)

/**
 * `AiAssistService#buildSectionChatPrompt` が組み立てる壁打ち(追加指示による再生成)プロンプトの形。
 * 記事プランの壁打ち(`ArticlePlanService#buildChatPrompt`)や画像プロンプト生成の壁打ち
 * (`AiAssistService#buildImagePromptChat`)と全く同じ
 * 「System: <指示文>(空行)(履歴/User・Assistant行…)User: <追加指示>(改行)Assistant: 」の形をしており、
 * 語尾に固定の "Assistant: "(直後は空、応答待ち)が来ることまでは他の壁打ちと共通で見分けが付かない。
 */
const SECTION_CHAT_PROMPT_PATTERN = /^System: [\s\S]*\nAssistant: $/;

/**
 * `SECTION_PROMPT_TEMPLATES`(body/lead/lead-subsections)がいずれも冒頭に持つ、
 * セクション生成専用の指示文言(出典: AiAssistService.SECTION_PROMPT_TEMPLATES)。
 * 記事プランの壁打ち(`PLAN_SYSTEM_MARKER`)や画像プロンプト生成の壁打ち
 * (`IMAGE_PROMPT_SYSTEM_PROMPT` = 「あなたは画像生成AI(Stable Diffusion)向けの…」)は
 * この文言を持たないため、{@link SECTION_CHAT_PROMPT_PATTERN}(形だけの判定)と組み合わせて
 * 初めてセクション生成の壁打ちだけを取り出せる。
 */
const SECTION_CHAT_SYSTEM_MARKER = 'あなたはブログ執筆アシスタントです。';

/**
 * プロンプトに積まれた追加指示(User行)の件数。記事プランの{@link planUserTurnCount}と同じ考え方で、
 * 「直前までの文脈(履歴)がLLMへ渡っているか」を応答の中身からシナリオが観測できるようにする。
 * 同じ入力なら同じ件数になるので決定性は崩れない。
 */
function sectionChatUserTurnCount(prompt) {
  return prompt.split('\n').filter((line) => line.startsWith('User: ')).length;
}

/**
 * セクション生成の壁打ち用の応答。用途が判別できなければ null を返し、呼び元の一般判定へ委ねる。
 *
 * 一般判定(`completionFor`)より**先に**通す必要がある。{@link SECTION_CHAT_SYSTEM_MARKER}や
 * basePromptに含まれる「記事タイトル」は「記事」を含むため、後ろに置くと一般判定の
 * {@link DRAFT_COMPLETION} に吸われて履歴・追加指示が応答へ反映されない
 * (このIssue自体が報告している症状)。
 *
 * 初回生成(historyもmessageも無い、通常のセクション生成)は"System: "で始まらず
 * "Assistant: "で終わらないため、ここには来ず従来どおり一般判定へ委ねられ、
 * {@link DRAFT_COMPLETION} を返す(既存シナリオ「セクション生成が、指定した見出し配下の本文
 * として返る」を壊さない)。
 */
function sectionChatCompletionFor(prompt) {
  if (!SECTION_CHAT_PROMPT_PATTERN.test(prompt)) return null;
  if (!prompt.includes(SECTION_CHAT_SYSTEM_MARKER)) return null;
  return `E2Eスタブのセクション再生成です(直前までの追加指示 ${sectionChatUserTurnCount(prompt)} 件)。`
    + '直前までの内容を踏まえて書き直しました。';
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
  const sectionChatCompletion = sectionChatCompletionFor(prompt);
  if (sectionChatCompletion !== null) {
    return sectionChatCompletion;
  }
  const jsonCompletion = jsonFormatCompletionFor(prompt);
  if (jsonCompletion !== null) {
    return jsonCompletion;
  }
  const tagDesignMarker = TAG_DESIGN_MARKER_PATTERN.exec(prompt);
  if (tagDesignMarker !== null && p.includes('```css')) {
    return tagDesignMarkedCompletion(tagDesignMarker[0]);
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

/**
 * 直近に受け取ったリクエストの `model` フィールドの履歴(issue #1148 / AT-8-3)。
 *
 * LLMモデル/プロバイダーの選択(ProjectLlmModelController)が実際の生成要求に反映されるかは、
 * 応答内容(プロンプトの特徴語で決まり、モデル名を反映しない)からは確認できない。
 * `/__control/state` の拡張状態(extraState)として公開し、受け入れテストが
 * 「選択したモデルがそのままリクエストに載っているか」を直接検査できるようにする。
 *
 * 「直近1件」ではなく履歴にするのは、このスタブへは複数の受け入れシナリオが並列に
 * リクエストを送るため、1件しか覚えないと自分のリクエストの直後に他シナリオの
 * リクエストが割り込んで上書きし、確認前に消えてしまうことがある(実測で発生した)。
 */
const RECENT_MODELS_LIMIT = 50;
let recentModels = [];

// ------------------------------------ 画像入力(issue #1600)

/**
 * 画像入力(OpenAI互換の content 配列に image_url が含まれる要求)への固定のタグ。
 * アップロード画像のAIタグ付け(UploadedImageTagService)が受ける。文字だけのタグ提案
 * ({@link IMAGE_TAGS_JSON_TAGS})とは別の値にしてあるので、受け入れテストは「画像を見て付いたタグ」を
 * 見分けられる。
 */
const VISION_TAGS_JSON_TAGS = ['e2e-stub-vision-tag-a', 'e2e-stub-vision-tag-b', 'e2e-stub-vision-tag-c'];
const VISION_TAGS_JSON_COMPLETION = JSON.stringify({ tags: VISION_TAGS_JSON_TAGS });

/** GPS位置情報とみなす目印。受け入れテスト(imageUpload.steps.ts の GPS_MARKER)が画像へ埋め込む。 */
const GPS_MARKER = 'GPS-35.6586N-139.7454E';

/**
 * 受け取った画像の素性の履歴(`/__control/state` の `imageRequests`)。
 * 「AIへ送られた画像が、保存済みの変換後の画像と同一で、EXIF/GPSを含まない」を、
 * 外から検査できるようにするために公開する(`recentModels` と同じ理由で履歴にし、50件に切り詰める)。
 */
const IMAGE_REQUESTS_LIMIT = 50;
let imageRequests = [];

/** content が文字列ならそのまま、配列なら text パーツだけを連結する。 */
function contentText(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .filter((part) => part && part.type === 'text')
    .map((part) => part.text || '')
    .join('\n');
}

/** メッセージ中の image_url パーツをすべて集める。 */
function imageUrls(messages) {
  const urls = [];
  for (const message of messages) {
    if (!Array.isArray(message.content)) continue;
    for (const part of message.content) {
      if (part && part.type === 'image_url') {
        urls.push((part.image_url && part.image_url.url) || '');
      }
    }
  }
  return urls;
}

/** data URL から画像の素性を作る。data URL でなければバイト数0・MIME null の空の素性。 */
function describeImage(model, url) {
  const match = /^data:([^;,]+);base64,(.*)$/s.exec(url);
  if (!match) {
    return {
      model, mimeType: null, bytes: 0, sha256: null, containsExif: false, containsGpsMarker: false,
    };
  }
  const bytes = Buffer.from(match[2], 'base64');
  const text = bytes.toString('latin1');
  return {
    model,
    mimeType: match[1],
    bytes: bytes.length,
    sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
    containsExif: text.includes('Exif'),
    containsGpsMarker: text.includes(GPS_MARKER),
  };
}

// ------------------------------------ Ollama の POST /api/pull(issue #1675)

/**
 * Ollama のモデル pull(`POST /api/pull`、NDJSON のストリーミング応答)。ai-service の
 * OllamaPullJobRunner が叩く。受け入れ環境では実 Ollama を起動しない(#1090)ので、これが代わりに受ける。
 * 受け取ったモデル名を `/__control/state` の `pullRequests` に残し、「実効接続先へそのモデル名で
 * pull が送られた」を受け入れテストが確かめられるようにする(`recentModels` と同じ理由で履歴にして50件に切り詰める)。
 *
 * 応答はモデル名で決まる(決定性のため、時刻や乱数は使わない):
 *   - `fail` を含む    : 進捗を1行流してから `{"error": …}` を流して終わる(ストリームの途中の失敗)
 *   - `missing` を含む : HTTP 404 と `{"error": …}`(Ollama が存在しないモデルに返す形)
 *   - `slow` を含む    : 数秒かけて completed / total を流してから success(画面で進捗を観測できる)
 *   - それ以外         : すぐに進捗を流して success
 */
const PULL_REQUESTS_LIMIT = 50;
let pullRequests = [];
const PULL_ERROR = 'pull model manifest: file does not exist';
/** slow のときの1歩ごとの待ち(ms)と歩数。合計およそ5秒で、画面のポーリング(2秒)に複数回かかる。 */
const PULL_SLOW_STEP_MS = 500;
const PULL_SLOW_STEPS = 10;
const PULL_LAYER_TOTAL = 1_000_000;

function recordPull(body) {
  let model = '';
  try {
    const parsed = JSON.parse(body || '{}');
    // 現行のフィールドは model、古い形式は name。
    model = String(parsed.model || parsed.name || '');
  } catch {
    // 解析できないボディでも応答は返す。
  }
  pullRequests.push(model);
  if (pullRequests.length > PULL_REQUESTS_LIMIT) {
    pullRequests.shift();
  }
  return model;
}

const sleepMs = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function pullProgressLine(completed) {
  return JSON.stringify({
    status: 'pulling e2e-stub-layer',
    digest: 'sha256:e2e-stub-layer',
    total: PULL_LAYER_TOTAL,
    completed,
  });
}

async function streamPull(res, model) {
  res.writeHead(200, { 'Content-Type': 'application/x-ndjson' });
  res.write(`${JSON.stringify({ status: 'pulling manifest' })}\n`);
  if (model.includes('fail')) {
    res.write(`${JSON.stringify({ error: PULL_ERROR })}\n`);
    res.end();
    return;
  }
  const steps = model.includes('slow') ? PULL_SLOW_STEPS : 2;
  for (let step = 1; step <= steps; step++) {
    if (model.includes('slow')) {
      await sleepMs(PULL_SLOW_STEP_MS);
    }
    // 最後の1歩の手前までを進捗として流す(100%の行は success の直前に置かない)。
    res.write(`${pullProgressLine(Math.floor((PULL_LAYER_TOTAL * step) / (steps + 1)))}\n`);
  }
  res.write(`${JSON.stringify({ status: 'verifying sha256 digest' })}\n`);
  res.write(`${JSON.stringify({ status: 'success' })}\n`);
  res.end();
}

/** GET /v1/models が返すモデルID(issue #1674)。AT(model-selection / llm-model-dropdown)がこの値を前提にする。 */
const LISTED_OPENAI_MODELS = ['e2e-stub-gpt-a', 'e2e-stub-gpt-b'];
/** GET /api/tags が返すモデル名(issue #1674)。 */
const LISTED_OLLAMA_MODELS = ['e2e-stub-ollama-a:1b', 'e2e-stub-ollama-b:1b'];

createStub({
  name: 'llm',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({
    error: { message: `[${name}] forced ${status}`, type: 'stub_error', code: String(status) },
  }),
  onReset: () => {
    recentModels = [];
    imageRequests = [];
    pullRequests = [];
  },
  extraState: () => ({
    recentModels: [...recentModels],
    imageRequests: [...imageRequests],
    pullRequests: [...pullRequests],
  }),
  async handle({ method, pathname, body, res, sendJson }) {
    // Ollama固有の GET /api/ps(issue #1397)。platform-service の「連携サービスの状況」が
    // ロード中モデルの size_vram から演算デバイスを解決する。決定性のため、VRAMに載っていない
    // (size_vram = 0、つまり cpu)モデルを1件返す。GPU非搭載ホストでも同じ結果になる。
    if (method === 'GET' && pathname === '/api/ps') {
      sendJson(res, 200, {
        models: [{ name: 'e2e-stub', model: 'e2e-stub', size: 4_000_000_000, size_vram: 0 }],
      });
      return true;
    }

    // モデル一覧(issue #1674)。ai-service の ProviderModelCatalog が、プロジェクト画面のモデル
    // ドロップダウンの選択肢を取るために叩く。決定的な2件ずつ(現在のモデルと異なるものを選べるように)。
    // OpenAI / Anthropic 互換の GET /v1/models。
    if (method === 'GET' && pathname === '/v1/models') {
      sendJson(res, 200, { object: 'list', data: LISTED_OPENAI_MODELS.map((id) => ({ id, object: 'model', owned_by: 'e2e-stub' })) });
      return true;
    }
    // Ollama のネイティブ GET /api/tags。
    if (method === 'GET' && pathname === '/api/tags') {
      sendJson(res, 200, { models: LISTED_OLLAMA_MODELS.map((name) => ({ name, model: name, size: 1_000_000 })) });
      return true;
    }

    if (method === 'POST' && pathname === '/api/pull') {
      const model = recordPull(body);
      if (model.includes('missing')) {
        sendJson(res, 404, { error: PULL_ERROR });
        return true;
      }
      await streamPull(res, model);
      return true;
    }

    // OpenAI互換クライアントは baseUrl の末尾に /v1 を含める流儀もあるため、両方を受ける。
    if (method !== 'POST' || !/\/(v1\/)?chat\/completions$/.test(pathname)) return false;

    let prompt = '';
    let images = [];
    try {
      const parsed = JSON.parse(body || '{}');
      const messages = parsed.messages || [];
      prompt = messages.map((m) => contentText(m.content)).join('\n');
      images = imageUrls(messages);
      for (const url of images) {
        imageRequests.push(describeImage(parsed.model, url));
        if (imageRequests.length > IMAGE_REQUESTS_LIMIT) {
          imageRequests.shift();
        }
      }
      if (typeof parsed.model === 'string') {
        recentModels.push(parsed.model);
        if (recentModels.length > RECENT_MODELS_LIMIT) {
          recentModels.shift();
        }
      }
    } catch {
      // 解析できないボディでも応答は返す(呼び元の形式差で落とさない)。
    }

    // 入力による失敗誘発。制御エンドポイントを使えない経路(既存 spec)との互換のために残す。
    if (prompt.includes('FORCE_LLM_ERROR')) {
      sendJson(res, 500, { error: { message: 'forced error for E2E (FORCE_LLM_ERROR)' } });
      return true;
    }

    // 画像入力付きの要求には、プロンプトの特徴語に関わらず固定のビジョン用タグを返す(issue #1600)。
    const completion = images.length > 0 ? VISION_TAGS_JSON_COMPLETION : completionFor(prompt);

    sendJson(res, 200, {
      id: 'chatcmpl-e2e-stub',
      object: 'chat.completion',
      created: FIXED_CREATED,
      model: 'e2e-stub',
      choices: [
        { index: 0, message: { role: 'assistant', content: completion }, finish_reason: 'stop' },
      ],
      usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
    });
    return true;
  },
});
