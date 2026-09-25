/**
 * 本文中のカスタムタグ(issue #522)補完のうち、vscode APIに依存しない純粋なロジック。
 *
 * frontMatterCompletionLogic.tsと同様の理由でテスト容易性のため分離し、vscode.CompletionItem等
 * への変換はbodyCustomTagCompletionProvider.ts側で行う。
 *
 * カスタムタグは`[tagname]content[/tagname]`(INLINE)または`[tagname]\ncontent\n[/tagname]`(BLOCK)
 * の記法で本文中に埋め込む(CustomTagRenderService参照)。ここでは開始タグの`[`直後にカーソルが
 * あるかどうかだけを判定し、タグ名候補の絞り込みに使うprefixを返す。
 */

import { FRONT_MATTER_DELIMITER, findClosingDelimiterLine } from './frontMatterCompletionLogic';

export interface BodyCustomTagCompletionContext {
  /** カーソルより手前に既に入力されているタグ名の断片(絞り込みに使う)。 */
  prefix: string;
  /** 補完候補の確定時に置き換える範囲(行内の文字位置、開始)。 */
  replaceStart: number;
  /** 補完候補の確定時に置き換える範囲(行内の文字位置、終了)。 */
  replaceEnd: number;
}

// CustomTagRenderServiceのタグ名パターン(`[a-zA-Z][a-zA-Z0-9_-]*`)に合わせる。
const OPEN_TAG_PATTERN = /\[([a-zA-Z][a-zA-Z0-9_-]*)?$/;

/**
 * カーソル位置(lineNumber行目、charPosition文字目)が本文中のカスタムタグ開始タグ
 * (`[tagname`の途中)にあるかどうかを判定する。frontmatter部分では判定しない。
 */
export function detectBodyCustomTagCompletionContext(
  lines: string[],
  lineNumber: number,
  charPosition: number
): BodyCustomTagCompletionContext | undefined {
  if (isWithinFrontMatter(lines, lineNumber)) {
    return undefined;
  }

  const lineText = lines[lineNumber] ?? '';
  const beforeCursor = lineText.slice(0, charPosition);
  const match = OPEN_TAG_PATTERN.exec(beforeCursor);
  if (!match) {
    return undefined;
  }

  const prefix = match[1] ?? '';
  return {
    prefix,
    replaceStart: charPosition - prefix.length,
    replaceEnd: charPosition,
  };
}

function isWithinFrontMatter(lines: string[], lineNumber: number): boolean {
  if (lines[0] !== FRONT_MATTER_DELIMITER) {
    return false;
  }
  const closingLine = findClosingDelimiterLine(lines);
  // 閉じの---が無い(frontmatterが未終端)場合、本文の開始位置が確定しないため対象外とする。
  if (closingLine === -1) {
    return true;
  }
  return lineNumber <= closingLine;
}
