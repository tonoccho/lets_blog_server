import { detectBodyCustomTagCompletionContext } from '../bodyCustomTagCompletionLogic';

function toLines(text: string): string[] {
  return text.split('\n');
}

describe('detectBodyCustomTagCompletionContext', () => {
  it('本文中で[の直後にカーソルがある場合、空のprefixとして判定する', () => {
    const lines = toLines('本文です[');
    const result = detectBodyCustomTagCompletionContext(lines, 0, '本文です['.length);
    expect(result).toEqual({ prefix: '', replaceStart: 5, replaceEnd: 5 });
  });

  it('タグ名を途中まで入力した状態でも判定し、prefixに反映する', () => {
    const lines = toLines('本文[warn');
    const result = detectBodyCustomTagCompletionContext(lines, 0, '本文[warn'.length);
    expect(result).toEqual({ prefix: 'warn', replaceStart: 3, replaceEnd: 7 });
  });

  it('[の後に]が既に入力されている場合は判定しない', () => {
    const lines = toLines('本文[blogcard]');
    expect(detectBodyCustomTagCompletionContext(lines, 0, '本文[blogcard]'.length)).toBeUndefined();
  });

  it('[が無い行では判定しない', () => {
    const lines = toLines('ただの本文です');
    expect(detectBodyCustomTagCompletionContext(lines, 0, 3)).toBeUndefined();
  });

  it('frontmatter内では判定しない', () => {
    const lines = toLines('---\ntitle: サンプル\n---\n本文[');
    expect(detectBodyCustomTagCompletionContext(lines, 1, 'title: サンプル'.length)).toBeUndefined();
  });

  it('frontmatterの閉じの---の行でも判定しない', () => {
    const lines = toLines('---\ntitle: サンプル\n---\n本文[');
    expect(detectBodyCustomTagCompletionContext(lines, 2, 0)).toBeUndefined();
  });

  it('frontmatterの外側(本文)では判定する', () => {
    const lines = toLines('---\ntitle: サンプル\n---\n本文[');
    const result = detectBodyCustomTagCompletionContext(lines, 3, '本文['.length);
    expect(result).toEqual({ prefix: '', replaceStart: 3, replaceEnd: 3 });
  });

  it('frontmatterの閉じが無い場合は判定しない(本文開始位置が不明なため)', () => {
    const lines = toLines('---\ntitle: サンプル\n本文[');
    expect(detectBodyCustomTagCompletionContext(lines, 2, '本文['.length)).toBeUndefined();
  });

  it('frontmatterが存在しない文書では先頭行でも判定する', () => {
    const lines = toLines('本文[toc');
    const result = detectBodyCustomTagCompletionContext(lines, 0, '本文[toc'.length);
    expect(result).toEqual({ prefix: 'toc', replaceStart: 3, replaceEnd: 6 });
  });
});
