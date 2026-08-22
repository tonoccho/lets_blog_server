/**
 * frontmatter補完(issue #521)のうち、vscode APIに依存しない純粋なロジック。
 *
 * どのフィールド(status/categories/tags)のどの位置を補完すべきかの判定は、proofreadLogic.tsと
 * 同様の理由でテスト容易性のため分離し、vscode.CompletionItem等への変換は
 * frontMatterCompletionProvider.ts側で行う。
 */

const FRONT_MATTER_DELIMITER = '---';

export type FrontMatterCompletionField = 'status' | 'categories' | 'tags';

/** カーソル位置が補完対象だった場合の判定結果。 */
export interface FrontMatterCompletionContext {
  field: FrontMatterCompletionField;
  /** カーソルより手前に既に入力されている値の断片(絞り込みに使う)。 */
  prefix: string;
  /** 補完候補の確定時に置き換える範囲(行内の文字位置、開始)。 */
  replaceStart: number;
  /** 補完候補の確定時に置き換える範囲(行内の文字位置、終了)。 */
  replaceEnd: number;
}

const STATUS_LINE_PATTERN = /^status\s*:\s*/;
const LIST_ITEM_PATTERN = /^\s*-\s*/;
const CATEGORIES_KEY_PATTERN = /^categories\s*:\s*$/;
const TAGS_KEY_PATTERN = /^tags\s*:\s*$/;

/**
 * カーソル位置(lineNumber行目、charPosition文字目)がfrontmatterの
 * status/categories/tagsの値部分にあるかどうかを判定する。
 *
 * @param lines ドキュメント全体を改行で分割した行の配列。
 */
export function detectFrontMatterCompletionContext(
  lines: string[],
  lineNumber: number,
  charPosition: number
): FrontMatterCompletionContext | undefined {
  if (lines[0] !== FRONT_MATTER_DELIMITER) {
    return undefined;
  }
  const closingLine = findClosingDelimiterLine(lines);
  if (closingLine === -1 || lineNumber <= 0 || lineNumber >= closingLine) {
    return undefined;
  }

  const lineText = lines[lineNumber] ?? '';

  const statusMatch = STATUS_LINE_PATTERN.exec(lineText);
  if (statusMatch && charPosition >= statusMatch[0].length) {
    return {
      field: 'status',
      prefix: lineText.slice(statusMatch[0].length, charPosition),
      replaceStart: statusMatch[0].length,
      replaceEnd: lineText.length,
    };
  }

  const listItemMatch = LIST_ITEM_PATTERN.exec(lineText);
  if (listItemMatch && charPosition >= listItemMatch[0].length) {
    const field = findEnclosingListField(lines, lineNumber - 1);
    if (field) {
      return {
        field,
        prefix: lineText.slice(listItemMatch[0].length, charPosition),
        replaceStart: listItemMatch[0].length,
        replaceEnd: lineText.length,
      };
    }
  }

  return undefined;
}

function findClosingDelimiterLine(lines: string[]): number {
  for (let i = 1; i < lines.length; i++) {
    if (lines[i] === FRONT_MATTER_DELIMITER) {
      return i;
    }
  }
  return -1;
}

/**
 * リスト項目(`  - 値`)の行から上方向へ辿り、そのリストが categories/tags どちらの
 * キーに属するかを探す。他のリスト項目はスキップし、キー行に到達しなければ(=対象外の
 * フィールドのリストであれば)undefinedを返す。
 */
function findEnclosingListField(lines: string[], fromLine: number): 'categories' | 'tags' | undefined {
  for (let i = fromLine; i >= 1; i--) {
    const line = lines[i];
    if (CATEGORIES_KEY_PATTERN.test(line)) {
      return 'categories';
    }
    if (TAGS_KEY_PATTERN.test(line)) {
      return 'tags';
    }
    if (LIST_ITEM_PATTERN.test(line)) {
      continue;
    }
    return undefined;
  }
  return undefined;
}
