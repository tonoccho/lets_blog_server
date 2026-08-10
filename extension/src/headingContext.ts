/**
 * カーソル位置のMarkdown本文を解析し、AI文章生成(letsBlog.generateSection)が
 * どのプロンプトモードを使うべきかを判定する。判定方針(spec/requirements-p20.md):
 * 1. 本文先頭(直前に見出しが無い) → 記事全体の構成を考慮したリード文
 * 2. 直下にサブセクション(1階層深い見出し)がある → サブセクションを考慮したリード文
 * 3. サブセクションが無い → セクションタイトルを考慮した本文
 */

export interface HeadingInfo {
  line: number;
  level: number;
  text: string;
}

export interface SectionContext {
  mode: 'lead' | 'lead-subsections' | 'body';
  heading?: string;
  headingLevel?: number;
  subsectionHeadings: string[];
  precedingContext: string;
}

/**
 * フェンスコードブロック(```/~~~)内の行を除外して見出し(ATX形式)を抽出する。
 * Issue本文の見出し構造抽出(issueParser.ts)でも同じ判定が必要なためエクスポートしている。
 */
export function extractHeadings(lines: string[]): HeadingInfo[] {
  const headings: HeadingInfo[] = [];
  let inFence = false;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (inFence) continue;

    const match = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line);
    if (match) {
      headings.push({ line: i, level: match[1].length, text: match[2].trim() });
    }
  }
  return headings;
}

export function resolveSectionContext(fullText: string, cursorLine: number): SectionContext {
  const lines = fullText.split(/\r?\n/);
  const headings = extractHeadings(lines);
  const priorHeadings = headings.filter((h) => h.line < cursorLine);

  if (priorHeadings.length === 0) {
    // 本文先頭: まだどの見出しの配下にもいない。記事全体の構成(最上位見出し)を出典として渡す。
    const topLevel = headings.length > 0 ? Math.min(...headings.map((h) => h.level)) : 1;
    const outline = headings.filter((h) => h.level === topLevel).map((h) => h.text);
    return { mode: 'lead', subsectionHeadings: outline, precedingContext: '' };
  }

  const current = priorHeadings[priorHeadings.length - 1];
  const currentIndex = headings.indexOf(current);

  // currentと同階層以上の次の見出しまでが、currentセクションの範囲。
  let boundary = headings.length;
  for (let i = currentIndex + 1; i < headings.length; i++) {
    if (headings[i].level <= current.level) {
      boundary = i;
      break;
    }
  }

  const between = headings.slice(currentIndex + 1, boundary);
  const childLevel = between.length > 0 ? Math.min(...between.map((h) => h.level)) : null;
  const subsectionHeadings = childLevel !== null
    ? between.filter((h) => h.level === childLevel).map((h) => h.text)
    : [];

  if (subsectionHeadings.length > 0) {
    return {
      mode: 'lead-subsections',
      heading: current.text,
      headingLevel: current.level,
      subsectionHeadings,
      precedingContext: '',
    };
  }

  const precedingContext = lines.slice(current.line + 1, cursorLine).join('\n').trim();
  return {
    mode: 'body',
    heading: current.text,
    headingLevel: current.level,
    subsectionHeadings: [],
    precedingContext,
  };
}
