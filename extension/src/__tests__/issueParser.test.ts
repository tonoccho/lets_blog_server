import { extractIssueOutline, formatOutlineAsMarkdown } from '../issueParser';

describe('extractIssueOutline', () => {
  it('見出しから構成を抽出し、最上位を##に揃える', () => {
    const body = ['# 記事の狙い', '本文', '## 背景', '本文', '## 手順', '本文'].join('\n');
    expect(extractIssueOutline(body)).toEqual([
      { level: 2, text: '記事の狙い' },
      { level: 3, text: '背景' },
      { level: 3, text: '手順' },
    ]);
  });

  it('既に##始まりの見出しはレベルを保つ', () => {
    const body = ['## 導入', '## 実践', '### 手順1'].join('\n');
    expect(extractIssueOutline(body)).toEqual([
      { level: 2, text: '導入' },
      { level: 2, text: '実践' },
      { level: 3, text: '手順1' },
    ]);
  });

  it('Issueテンプレートの定型見出しは構成に含めない', () => {
    const body = ['## 概要', '説明', '## 実装内容', '- やること', '## 受け入れ基準', '- [ ] 条件'].join('\n');
    // 定型見出しを除くと見出しが残らないため、箇条書きへフォールバックする。
    expect(extractIssueOutline(body)).toEqual([
      { level: 2, text: 'やること' },
      { level: 2, text: '条件' },
    ]);
  });

  it('定型見出しと記事見出しが混在する場合は記事見出しだけを取る', () => {
    const body = ['## 概要', '説明', '## Dockerの基礎', '## Composeの使い方'].join('\n');
    expect(extractIssueOutline(body)).toEqual([
      { level: 2, text: 'Dockerの基礎' },
      { level: 2, text: 'Composeの使い方' },
    ]);
  });

  it('見出しが無い場合は箇条書きから構成を起こす', () => {
    const body = ['この記事で書くこと', '', '- 導入', '  - 前提知識', '  - 環境', '- まとめ'].join('\n');
    expect(extractIssueOutline(body)).toEqual([
      { level: 2, text: '導入' },
      { level: 3, text: '前提知識' },
      { level: 3, text: '環境' },
      { level: 2, text: 'まとめ' },
    ]);
  });

  it('チェックボックス記法の箇条書きからも本文だけを取り出す', () => {
    const body = ['- [ ] 導入を書く', '- [x] まとめを書く'].join('\n');
    expect(extractIssueOutline(body)).toEqual([
      { level: 2, text: '導入を書く' },
      { level: 2, text: 'まとめを書く' },
    ]);
  });

  it('*と+の箇条書き記法も扱う', () => {
    expect(extractIssueOutline(['* 章1', '+ 章2'].join('\n'))).toEqual([
      { level: 2, text: '章1' },
      { level: 2, text: '章2' },
    ]);
  });

  it('タブと4スペースのインデントを同じ階層として扱う', () => {
    const tabbed = extractIssueOutline(['- 親', '\t- 子'].join('\n'));
    const spaced = extractIssueOutline(['- 親', '    - 子'].join('\n'));
    expect(tabbed).toEqual([
      { level: 2, text: '親' },
      { level: 3, text: '子' },
    ]);
    expect(spaced).toEqual(tabbed);
  });

  it('フェンスコードブロック内の見出しや箇条書きは無視する', () => {
    const body = ['## 実際の見出し', '```sh', '# コメント', '- リストではない', '```'].join('\n');
    expect(extractIssueOutline(body)).toEqual([{ level: 2, text: '実際の見出し' }]);
  });

  it('コードブロックしか無い場合は空を返す', () => {
    const body = ['```', '- リストではない', '```'].join('\n');
    expect(extractIssueOutline(body)).toEqual([]);
  });

  it('空の本文では空配列を返す', () => {
    expect(extractIssueOutline('')).toEqual([]);
    expect(extractIssueOutline('   \n  ')).toEqual([]);
  });

  it('見出しも箇条書きも無い散文では空配列を返す', () => {
    expect(extractIssueOutline('ただの説明文です。\nもう一行。')).toEqual([]);
  });

  it('レベル7以上にはせず6で頭打ちにする', () => {
    const body = ['- a', ' - b', '  - c', '   - d', '    - e', '     - f', '      - g'].join('\n');
    const levels = extractIssueOutline(body).map((i) => i.level);
    expect(Math.max(...levels)).toBe(6);
  });
});

describe('formatOutlineAsMarkdown', () => {
  it('見出しレベルに応じたMarkdownへ整形する', () => {
    const markdown = formatOutlineAsMarkdown([
      { level: 2, text: '導入' },
      { level: 3, text: '前提' },
    ]);
    expect(markdown).toBe('## 導入\n\n### 前提');
  });

  it('空の構成では空文字を返す', () => {
    expect(formatOutlineAsMarkdown([])).toBe('');
  });

  it('抽出結果をそのまま整形して往復できる', () => {
    const body = ['## 導入', '### 前提', '## まとめ'].join('\n');
    expect(formatOutlineAsMarkdown(extractIssueOutline(body))).toBe('## 導入\n\n### 前提\n\n## まとめ');
  });
});
