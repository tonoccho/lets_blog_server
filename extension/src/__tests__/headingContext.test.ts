import { resolveSectionContext } from '../headingContext';

/** テスト内で行番号を数えやすいよう、行配列から本文を組み立てる。 */
function doc(...lines: string[]): string {
  return lines.join('\n');
}

describe('resolveSectionContext', () => {
  it('直前に見出しが無い場合はリード文モードになり、記事全体の最上位見出しを渡す', () => {
    const text = doc('', '## 章A', '本文', '## 章B', '本文');
    const context = resolveSectionContext(text, 0);
    expect(context.mode).toBe('lead');
    expect(context.subsectionHeadings).toEqual(['章A', '章B']);
    expect(context.precedingContext).toBe('');
    expect(context.heading).toBeUndefined();
  });

  it('見出しが1つも無い本文でもリード文モードで成立する', () => {
    const context = resolveSectionContext(doc('本文だけ', 'さらに本文'), 1);
    expect(context.mode).toBe('lead');
    expect(context.subsectionHeadings).toEqual([]);
  });

  it('最上位見出しが#の場合もその階層だけを構成として渡す', () => {
    const text = doc('', '# 記事', '## 章A', '# 記事2');
    const context = resolveSectionContext(text, 0);
    expect(context.subsectionHeadings).toEqual(['記事', '記事2']);
  });

  it('直下にサブセクションがある場合はサブセクション考慮リード文になる', () => {
    //                0        1       2         3       4         5
    const text = doc('## 章A', '前置き', '### 節1', '本文1', '### 節2', '本文2');
    const context = resolveSectionContext(text, 1);
    expect(context.mode).toBe('lead-subsections');
    expect(context.heading).toBe('章A');
    expect(context.headingLevel).toBe(2);
    expect(context.subsectionHeadings).toEqual(['節1', '節2']);
    expect(context.precedingContext).toBe('');
  });

  it('サブセクションが無い場合は本文モードになり、直前までの文脈を渡す', () => {
    //                0        1        2
    const text = doc('## 章A', '一行目', '二行目');
    const context = resolveSectionContext(text, 3);
    expect(context.mode).toBe('body');
    expect(context.heading).toBe('章A');
    expect(context.headingLevel).toBe(2);
    expect(context.subsectionHeadings).toEqual([]);
    expect(context.precedingContext).toBe('一行目\n二行目');
  });

  it('同階層の次の見出し以降はカレントセクションの範囲に含めない', () => {
    //                0        1       2         3       4
    const text = doc('## 章A', '本文A', '## 章B', '本文B', '### 節B1');
    const context = resolveSectionContext(text, 1);
    // 章Aの範囲は章Bの手前まで。章B配下の節B1をサブセクションとして拾わない。
    expect(context.mode).toBe('body');
    expect(context.heading).toBe('章A');
  });

  it('より深い階層が混在する場合、直下の階層だけをサブセクションとする', () => {
    //                0        1         2          3         4
    const text = doc('## 章A', '### 節1', '#### 項1', '### 節2', '本文');
    const context = resolveSectionContext(text, 1);
    expect(context.mode).toBe('lead-subsections');
    expect(context.subsectionHeadings).toEqual(['節1', '節2']);
  });

  it('フェンスコードブロック内の#行は見出しとして扱わない', () => {
    const text = doc('## 章A', '```sh', '# これはコメント', '```', '本文');
    const context = resolveSectionContext(text, 4);
    expect(context.mode).toBe('body');
    expect(context.heading).toBe('章A');
    expect(context.subsectionHeadings).toEqual([]);
  });

  it('~~~ のフェンスも同様に扱う', () => {
    const text = doc('## 章A', '~~~', '### 見出しではない', '~~~', '本文');
    const context = resolveSectionContext(text, 4);
    expect(context.subsectionHeadings).toEqual([]);
  });

  it('閉じ記号(#)付きの見出し記法からテキストのみを取り出す', () => {
    const text = doc('## 章A ##', '本文');
    const context = resolveSectionContext(text, 1);
    expect(context.heading).toBe('章A');
  });

  it('CRLF改行の本文でも行を正しく分割する', () => {
    const context = resolveSectionContext('## 章A\r\n一行目\r\n', 2);
    expect(context.mode).toBe('body');
    expect(context.heading).toBe('章A');
    expect(context.precedingContext).toBe('一行目');
  });

  it('見出し直後(文脈なし)でも本文モードとして成立する', () => {
    const context = resolveSectionContext(doc('## 章A', ''), 1);
    expect(context.mode).toBe('body');
    expect(context.precedingContext).toBe('');
  });
});
