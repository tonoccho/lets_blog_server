import {
  computeBodyOffset,
  findFrontMatterFieldLine,
  findInvalidCategories,
  isValidStatus,
  locateContentIssues,
} from '../proofreadLogic';
import { ProofreadIssue } from '../schemas';

function issue(overrides: Partial<ProofreadIssue>): ProofreadIssue {
  return { type: 'typo', originalText: '', message: '', suggestion: null, ...overrides };
}

describe('computeBodyOffset', () => {
  it('front matterブロックの直後の位置を返す', () => {
    const text = '---\ntitle: サンプル\n---\n本文です。';
    const offset = computeBodyOffset(text);
    expect(text.slice(offset)).toBe('本文です。');
  });

  it('front matterが無い場合は0を返す', () => {
    expect(computeBodyOffset('見出しのない本文だけ')).toBe(0);
  });
});

describe('locateContentIssues', () => {
  it('originalTextが本文中に見つかれば位置を解決する', () => {
    const content = 'こんちには世界。今日もこんちには元気です。';
    const bodyOffset = 4;
    const issues = [issue({ originalText: 'こんちには' })];

    const located = locateContentIssues(content, bodyOffset, issues);

    expect(located).toHaveLength(1);
    expect(located[0].startOffset).toBe(bodyOffset + content.indexOf('こんちには'));
    expect(located[0].endOffset).toBe(located[0].startOffset + 'こんちには'.length);
  });

  it('同じ表現が複数回登場する場合、指摘の順番通りに別の出現位置へ対応付ける', () => {
    const content = '猫が好き。犬も好き。猫が好き。';
    const issues = [issue({ originalText: '猫が好き' }), issue({ originalText: '猫が好き' })];

    const located = locateContentIssues(content, 0, issues);

    expect(located).toHaveLength(2);
    expect(located[0].startOffset).toBe(content.indexOf('猫が好き'));
    expect(located[1].startOffset).toBe(content.lastIndexOf('猫が好き'));
    expect(located[0].startOffset).not.toBe(located[1].startOffset);
  });

  it('本文中に存在しないoriginalTextの指摘は除外する', () => {
    const located = locateContentIssues('こんにちは世界', 0, [issue({ originalText: '本文に無い文字列' })]);
    expect(located).toEqual([]);
  });
});

describe('findFrontMatterFieldLine', () => {
  const text = ['---', 'title: サンプル', 'status: draft', 'categories:', '  - 技術', '---', '本文'].join('\n');

  it('front matter中のkeyが書かれている行を返す', () => {
    const location = findFrontMatterFieldLine(text, 'status');
    expect(location).toEqual({ line: 2, startColumn: 0, endColumn: 'status: draft'.length });
  });

  it('存在しないkeyはundefinedを返す', () => {
    expect(findFrontMatterFieldLine(text, 'publish_scheduled_at')).toBeUndefined();
  });

  it('front matterが無い場合はundefinedを返す', () => {
    expect(findFrontMatterFieldLine('見出しのない本文だけ', 'status')).toBeUndefined();
  });
});

describe('findInvalidCategories', () => {
  it('サイトに存在しないカテゴリを返す', () => {
    expect(findInvalidCategories(['技術', '存在しないカテゴリ'], ['技術', '日記'])).toEqual(['存在しないカテゴリ']);
  });

  it('大文字小文字を区別せず一致を判定する', () => {
    expect(findInvalidCategories(['Tech'], ['tech'])).toEqual([]);
  });

  it('既存カテゴリ一覧が空(取得失敗/サイト未紐付け)の場合はチェックをスキップする', () => {
    expect(findInvalidCategories(['技術'], [])).toEqual([]);
  });

  it('categories未設定の場合は空配列を返す', () => {
    expect(findInvalidCategories(undefined, ['技術'])).toEqual([]);
  });
});

describe('isValidStatus', () => {
  it('有効な値であればtrueを返す', () => {
    expect(isValidStatus('draft', ['draft', 'publish'])).toBe(true);
  });

  it('無効な値であればfalseを返す', () => {
    expect(isValidStatus('unknown', ['draft', 'publish'])).toBe(false);
  });

  it('status未設定の場合はtrueを返す', () => {
    expect(isValidStatus(undefined, ['draft', 'publish'])).toBe(true);
  });

  it('validValuesが空(取得失敗)の場合はチェックをスキップしtrueを返す', () => {
    expect(isValidStatus('unknown', [])).toBe(true);
  });
});
