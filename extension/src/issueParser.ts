import { extractHeadings, HeadingInfo } from './headingContext';

/**
 * GitHub Issue本文から記事構成の下地を取り出す。
 *
 * Issueには既に「何を書くか」が箇条書きや見出しで整理されていることが多いが、
 * これまでは利用者が本文を見ながら構成欄へ手で写す必要があった。
 * ここで機械的に抽出し、Article Planパネルの構成案として初期表示する。
 */

/** 抽出した記事構成の1項目。 */
export interface OutlineItem {
  /** 見出しレベル(1〜6)。箇条書きから起こした場合はネストの深さに対応する。 */
  level: number;
  /** 見出しのテキスト。 */
  text: string;
}

/** Issue本文のうち、記事構成として持ち込みたくないテンプレート見出し。 */
const TEMPLATE_HEADINGS = [
  '概要',
  '問題点',
  '実装内容',
  '対象ファイル',
  '受け入れ基準',
  'ユーザーフロー',
  'データフロー',
  'api 要件',
  'コマンド',
  '補足',
  'メモ',
  'notes',
  'overview',
  'acceptance criteria',
];

const BULLET_PATTERN = /^(\s*)[-*+]\s+(?:\[[ xX]\]\s+)?(.+?)\s*$/;

/**
 * Issue本文から見出し構造を抽出する。
 *
 * 見出し(ATX形式)があればそれを使う。無い場合は箇条書きを構成の候補とみなす
 * (Issueテンプレートを使わず箇条書きだけで書かれることが多いため)。
 * どちらも無ければ空配列を返し、呼び出し側は従来どおりAIによる提案へ委ねる。
 */
export function extractIssueOutline(body: string): OutlineItem[] {
  if (!body || body.trim().length === 0) {
    return [];
  }
  const lines = body.split(/\r?\n/);

  const headings = extractHeadings(lines).filter((h) => !isTemplateHeading(h));
  if (headings.length > 0) {
    return normalizeLevels(headings.map((h) => ({ level: h.level, text: h.text })));
  }

  return normalizeLevels(extractBulletOutline(lines));
}

/**
 * Issueテンプレートの定型見出しかどうか。これらは記事の章立てではなく
 * Issueの記述枠のため、構成案から除外する。
 */
function isTemplateHeading(heading: HeadingInfo): boolean {
  const normalized = heading.text.trim().toLowerCase().replace(/\s+/g, ' ');
  return TEMPLATE_HEADINGS.some((template) => normalized === template);
}

/** 箇条書きのネスト量から見出しレベルを起こす。 */
function extractBulletOutline(lines: string[]): OutlineItem[] {
  const items: { indent: number; text: string }[] = [];
  let inFence = false;

  for (const line of lines) {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (inFence) continue;

    const match = BULLET_PATTERN.exec(line);
    if (match) {
      items.push({ indent: match[1].replace(/\t/g, '  ').length, text: match[2].trim() });
    }
  }
  if (items.length === 0) {
    return [];
  }

  // インデント量の種類を浅い順に並べ、その順位をレベルとする
  // (2スペース/4スペース/タブのどれで書かれていても同じ結果になるようにする)。
  const indents = [...new Set(items.map((i) => i.indent))].sort((a, b) => a - b);
  return items.map((item) => ({ level: indents.indexOf(item.indent) + 1, text: item.text }));
}

/**
 * 最上位が見出しレベル2(##)になるようレベルを揃える。
 * 記事本文ではタイトルがfront matterのtitleにあたるため、本文の見出しは##から始める。
 */
function normalizeLevels(items: OutlineItem[]): OutlineItem[] {
  if (items.length === 0) {
    return [];
  }
  const minLevel = Math.min(...items.map((i) => i.level));
  return items.map((item) => ({
    level: Math.min(item.level - minLevel + 2, 6),
    text: item.text,
  }));
}

/**
 * 抽出した構成をMarkdownの見出し列へ整形する。
 * Article Planパネルの構成案テキストエリアと、承認時にIssueへ書き戻す本文の両方で使う。
 */
export function formatOutlineAsMarkdown(items: OutlineItem[]): string {
  return items.map((item) => `${'#'.repeat(item.level)} ${item.text}`).join('\n\n');
}
