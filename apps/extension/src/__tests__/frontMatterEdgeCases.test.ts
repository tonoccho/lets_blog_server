import { buildArticleFrontMatter, parseArticle, stringifyArticle } from '../frontMatter';

/**
 * front matter の境界条件(issue #942 / AT-16 Layer 2、シナリオ24)。
 * 壊れたYAML・日本語・項目欠落は利用者の手書きで実際に起こる。既存の frontMatter.test.ts が
 * 正常系と正規化を押さえているため、ここでは「異常な入力に何が起きるか」を固定する。
 */
describe('parseArticle(壊れた・不足したfront matter)', () => {
  it('閉じていない引用符のYAMLは解析エラーとして投げる(黙って空にしない)', () => {
    const broken = '---\ntitle: "閉じていない\ncategories: [a\n---\n本文';
    expect(() => parseArticle(broken)).toThrow(/YAML|quoted|unexpected/i);
  });

  it('閉じていないリストのYAMLは解析エラーとして投げる', () => {
    const broken = '---\ntitle: 記事\ncategories: [技術, メモ\n---\n本文';
    expect(() => parseArticle(broken)).toThrow(/YAML|unexpected|flow/i);
  });

  it('キーが重複しているYAMLは解析エラーとして投げる(どちらが採用されたか曖昧にしない)', () => {
    const broken = '---\ntitle: 最初\ntitle: あと\n---\n本文';
    expect(() => parseArticle(broken)).toThrow(/YAML|duplicat/i);
  });

  it('区切りだけで中身が無いfront matterは空のdataとして扱う', () => {
    const parsed = parseArticle('---\n---\n本文だけ');
    expect(parsed.data).toEqual({});
    expect(parsed.content.trim()).toBe('本文だけ');
  });

  it('必須項目が欠けていても解析は成功し、欠けた項目はundefinedになる', () => {
    const parsed = parseArticle('---\ntitle: タイトルだけ\n---\n本文');
    expect(parsed.data.title).toBe('タイトルだけ');
    expect(parsed.data.slug).toBeUndefined();
    expect(parsed.data.status).toBeUndefined();
    expect(parsed.data.categories).toBeUndefined();
  });

  it('日本語の値・キー順・複数行の本文を往復しても壊れない', () => {
    const source = stringifyArticle({
      data: {
        title: '日本語のタイトル:コロン入り',
        slug: 'nihongo',
        status: 'draft',
        categories: ['技術メモ'],
        tags: ['受け入れテスト', 'VSCode拡張'],
      },
      content: '# 見出し\n\n段落1\n\n段落2\n',
    });

    const parsed = parseArticle(source);
    expect(parsed.data.title).toBe('日本語のタイトル:コロン入り');
    expect(parsed.data.categories).toEqual(['技術メモ']);
    expect(parsed.data.tags).toEqual(['受け入れテスト', 'VSCode拡張']);
    expect(parsed.content).toContain('段落1');
    expect(parsed.content).toContain('段落2');
  });

  it('本文が空の記事でもfront matterだけを取り出せる', () => {
    const parsed = parseArticle('---\ntitle: 空の本文\n---\n');
    expect(parsed.data.title).toBe('空の本文');
    expect(parsed.content.trim()).toBe('');
  });

  it('生成したfront matterは解析し直しても同じ値になる', () => {
    const generated = buildArticleFrontMatter({
      title: '日本語のタイトル',
      slug: 'nihongo-slug',
      categories: ['技術'],
      tags: ['タグ'],
    });
    const parsed = parseArticle(stringifyArticle({ data: generated, content: '本文' }));
    expect(parsed.data).toEqual(generated);
  });
});
