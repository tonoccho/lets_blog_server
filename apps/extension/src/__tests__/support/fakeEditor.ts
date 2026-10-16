/**
 * VS Code のテキストエディタの代替(issue #1840)。
 *
 * エディタと文書は拡張ホストにしか実体が無い実行基盤なので、メモリ上の文字列で動く最小の再現を使う。
 * 位置は {line, character}、範囲と選択は vscode スタブの Range / Selection で表す。
 * `edit()` は渡された builder の replace / insert を実際に文字列へ適用する(適用結果は `text` で読む)。
 */
import { Range, Selection } from '../../__mocks__/vscode';

interface Pos {
  line: number;
  character: number;
}

export class FakeEditor {
  public text: string;
  public saveCount = 0;
  public selection: Selection & { isEmpty: boolean; active: Pos };

  public readonly document: {
    languageId: string;
    uri: { fsPath: string; toString: () => string };
    getText: (range?: { start: Pos; end: Pos }) => string;
    positionAt: (offset: number) => Pos;
    lineAt: (line: number) => { text: string };
    save: () => Promise<boolean>;
  };

  constructor(
    text: string,
    public readonly fsPath = '/workspace/articles/my-article/article.md',
    languageId = 'markdown'
  ) {
    this.text = text;
    this.selection = this.makeSelection(0, 0);
    this.document = {
      languageId,
      uri: { fsPath, toString: () => `file://${fsPath}` },
      getText: (range) => (range ? this.text.slice(this.offsetAt(range.start), this.offsetAt(range.end)) : this.text),
      positionAt: (offset) => this.positionAt(offset),
      lineAt: (line) => ({ text: this.text.split('\n')[line] ?? '' }),
      save: async () => {
        this.saveCount += 1;
        return true;
      },
    };
  }

  positionAt(offset: number): Pos {
    const before = this.text.slice(0, offset).split('\n');
    return { line: before.length - 1, character: before[before.length - 1].length };
  }

  offsetAt(position: Pos): number {
    const lines = this.text.split('\n');
    let offset = 0;
    for (let i = 0; i < position.line; i += 1) offset += (lines[i]?.length ?? 0) + 1;
    return offset + position.character;
  }

  private makeSelection(startOffset: number, endOffset: number): Selection & { isEmpty: boolean; active: Pos } {
    const selection = new Selection(this.positionAt(startOffset), this.positionAt(endOffset)) as Selection & {
      isEmpty: boolean;
      active: Pos;
    };
    selection.isEmpty = startOffset === endOffset;
    return selection;
  }

  /** カーソルを offset に置く(選択なし)。 */
  setCursor(offset: number): void {
    this.selection = this.makeSelection(offset, offset);
  }

  /** 範囲 [start, end) を選択する。 */
  select(startOffset: number, endOffset: number): void {
    this.selection = this.makeSelection(startOffset, endOffset);
  }

  /** カーソルを line 行目(0 始まり)の先頭に置く。 */
  setCursorLine(line: number): void {
    this.setCursor(this.offsetAt({ line, character: 0 }));
  }

  async edit(
    callback: (builder: {
      replace: (range: { start: Pos; end: Pos }, text: string) => void;
      insert: (position: Pos, text: string) => void;
    }) => void
  ): Promise<boolean> {
    callback({
      replace: (range, text) => {
        const start = this.offsetAt(range.start);
        const end = this.offsetAt(range.end);
        this.text = this.text.slice(0, start) + text + this.text.slice(end);
      },
      insert: (position, text) => {
        const at = this.offsetAt(position);
        this.text = this.text.slice(0, at) + text + this.text.slice(at);
      },
    });
    return true;
  }

  /** 装飾の呼び出し記録(校正チェックのアンダーライン)。 */
  setDecorations = jest.fn();
}

export { Range };
