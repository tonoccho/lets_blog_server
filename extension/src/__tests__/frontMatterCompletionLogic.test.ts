import { detectFrontMatterCompletionContext } from '../frontMatterCompletionLogic';

function toLines(text: string): string[] {
  return text.split('\n');
}

describe('detectFrontMatterCompletionContext', () => {
  it('statusフィールドの値部分にカーソルがある場合、statusとして判定する', () => {
    const lines = toLines('---\ntitle: サンプル\nstatus: dra\n---\n本文');
    const result = detectFrontMatterCompletionContext(lines, 2, 'status: dra'.length);
    expect(result).toEqual({ field: 'status', prefix: 'dra', replaceStart: 8, replaceEnd: 11 });
  });

  it('コロン直後(値未入力)でもstatusとして判定する', () => {
    const lines = toLines('---\nstatus:\n---\n本文');
    const result = detectFrontMatterCompletionContext(lines, 1, 'status:'.length);
    expect(result).toEqual({ field: 'status', prefix: '', replaceStart: 7, replaceEnd: 7 });
  });

  it('カーソルがstatusのキー部分にある場合は判定しない', () => {
    const lines = toLines('---\nstatus: draft\n---\n本文');
    expect(detectFrontMatterCompletionContext(lines, 1, 3)).toBeUndefined();
  });

  it('categoriesのリスト項目にカーソルがある場合、categoriesとして判定する', () => {
    const lines = toLines('---\ncategories:\n  - 技\n---\n本文');
    const result = detectFrontMatterCompletionContext(lines, 2, '  - 技'.length);
    expect(result).toEqual({ field: 'categories', prefix: '技', replaceStart: 4, replaceEnd: 5 });
  });

  it('tagsのリスト項目にカーソルがある場合、tagsとして判定する', () => {
    const lines = toLines('---\ntags:\n  - foo\n---\n本文');
    const result = detectFrontMatterCompletionContext(lines, 2, '  - foo'.length);
    expect(result).toEqual({ field: 'tags', prefix: 'foo', replaceStart: 4, replaceEnd: 7 });
  });

  it('categoriesの2件目以降のリスト項目でも先頭のキー行まで遡って判定する', () => {
    const lines = toLines('---\ncategories:\n  - 技術\n  - 生活\n---\n本文');
    const result = detectFrontMatterCompletionContext(lines, 3, '  - 生活'.length);
    expect(result?.field).toBe('categories');
  });

  it('categories/tags以外のリストは判定しない', () => {
    const lines = toLines('---\nother_list:\n  - foo\n---\n本文');
    expect(detectFrontMatterCompletionContext(lines, 2, '  - foo'.length)).toBeUndefined();
  });

  it('front matterが存在しない場合は判定しない', () => {
    const lines = toLines('status: draft\n本文');
    expect(detectFrontMatterCompletionContext(lines, 0, 8)).toBeUndefined();
  });

  it('front matterの外側(本文側)にカーソルがある場合は判定しない', () => {
    const lines = toLines('---\nstatus: draft\n---\nstatus: draft');
    expect(detectFrontMatterCompletionContext(lines, 3, 8)).toBeUndefined();
  });

  it('閉じの---が無い場合は判定しない', () => {
    const lines = toLines('---\nstatus: draft\n本文');
    expect(detectFrontMatterCompletionContext(lines, 1, 8)).toBeUndefined();
  });
});
