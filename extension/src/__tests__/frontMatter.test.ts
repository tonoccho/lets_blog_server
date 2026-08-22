import * as path from 'path';
import {
  parseArticle,
  stringifyArticle,
  resolveLocalImagePath,
  extractLocalImageReferences,
  resolveFeaturedImageReference,
  guessImageMimeType,
  buildArticleFrontMatter,
  suggestSlugFromTitle,
  validateScheduledPublication,
} from '../frontMatter';

const BASE_DIR = path.resolve('/workspace/articles/sample');

describe('parseArticle', () => {
  it('front matterと本文を分離する', () => {
    const article = parseArticle('---\ntitle: サンプル\nstatus: draft\n---\n\n本文です。\n');
    expect(article.data.title).toBe('サンプル');
    expect(article.data.status).toBe('draft');
    expect(article.content.trim()).toBe('本文です。');
  });

  it('front matterが無い場合はdataが空になる', () => {
    const article = parseArticle('見出しのない本文だけ');
    expect(article.data).toEqual({});
    expect(article.content).toBe('見出しのない本文だけ');
  });

  it('単数形のcategoryキーをcategoriesへ正規化する', () => {
    const article = parseArticle('---\ntitle: サンプル\ncategory: 技術\n---\n\n本文です。\n');
    expect(article.data.categories).toEqual(['技術']);
    expect(article.data.category).toBeUndefined();
  });

  it('categoriesが既にある場合はcategoryを無視する', () => {
    const article = parseArticle(
      '---\ntitle: サンプル\ncategories:\n  - 既存\ncategory: 技術\n---\n\n本文です。\n'
    );
    expect(article.data.categories).toEqual(['既存']);
    expect(article.data.category).toBeUndefined();
  });

  it('廃止済みのwp_post_id/wp_post_url/wp_post_idsが残っていても取り除く', () => {
    const article = parseArticle(
      [
        '---',
        'title: サンプル',
        "wp_post_id: '130'",
        "wp_post_url: 'https://nzlife.tonoccho.com/?p=130'",
        'wp_post_ids:',
        "  local: '115'",
        "  production: '130'",
        '---',
        '',
        '本文です。',
      ].join('\n')
    );
    expect(article.data.wp_post_id).toBeUndefined();
    expect(article.data.wp_post_url).toBeUndefined();
    expect(article.data.wp_post_ids).toBeUndefined();
  });
});

describe('stringifyArticle', () => {
  it('front matter付きの文字列へ戻せる', () => {
    const output = stringifyArticle({ data: { title: 'タイトル' }, content: '本文' });
    expect(output).toContain('title: タイトル');
    expect(output).toContain('本文');
  });

  it('parseArticleと往復しても内容が保たれる', () => {
    const original = { data: { title: 'T', tags: ['a', 'b'] }, content: '本文\n' };
    const reparsed = parseArticle(stringifyArticle(original));
    expect(reparsed.data.title).toBe('T');
    expect(reparsed.data.tags).toEqual(['a', 'b']);
  });
});

describe('resolveLocalImagePath', () => {
  it('相対パスをbaseDir基準で解決する', () => {
    expect(resolveLocalImagePath(BASE_DIR, 'assets/eyecatch.png')).toBe(
      path.join(BASE_DIR, 'assets/eyecatch.png')
    );
  });

  it('先頭の"/"はサイトルート相対とみなし、OS絶対パスとして扱わない', () => {
    // path.resolveは"/assets/..."をFS絶対パスとして扱いbaseDirを無視してしまうため、
    // 先頭の"/"を除去してから解決していることを確認する。
    expect(resolveLocalImagePath(BASE_DIR, '/assets/eyecatch.png')).toBe(
      path.join(BASE_DIR, 'assets/eyecatch.png')
    );
  });
});

describe('extractLocalImageReferences', () => {
  it('本文中のローカル画像参照を抽出する', () => {
    const content = '![説明](assets/a.png)\n\n![別の画像](./assets/b.jpg)';
    expect(extractLocalImageReferences(content, BASE_DIR)).toEqual([
      { reference: 'assets/a.png', absolutePath: path.join(BASE_DIR, 'assets/a.png') },
      { reference: './assets/b.jpg', absolutePath: path.join(BASE_DIR, 'assets/b.jpg') },
    ]);
  });

  it('http(s)/プロトコル相対/data URIは対象外とする', () => {
    const content = [
      '![a](https://example.com/a.png)',
      '![b](http://example.com/b.png)',
      '![c](//example.com/c.png)',
      '![d](data:image/png;base64,AAAA)',
    ].join('\n');
    expect(extractLocalImageReferences(content, BASE_DIR)).toEqual([]);
  });

  it('同じ参照が複数回現れても1件にまとめる', () => {
    const content = '![一枚目](assets/a.png)\n![再掲](assets/a.png)';
    expect(extractLocalImageReferences(content, BASE_DIR)).toHaveLength(1);
  });

  it('タイトル付きの画像記法からもパスだけを取り出す', () => {
    const refs = extractLocalImageReferences('![説明](assets/a.png "タイトル")', BASE_DIR);
    expect(refs.map((r) => r.reference)).toEqual(['assets/a.png']);
  });

  it('画像参照が無い本文では空配列を返す', () => {
    expect(extractLocalImageReferences('本文のみ。[リンク](https://example.com)', BASE_DIR)).toEqual([]);
  });
});

describe('resolveFeaturedImageReference', () => {
  it('相対パスのfeatured_imageを解決する', () => {
    expect(resolveFeaturedImageReference({ featured_image: 'assets/eyecatch.png' }, BASE_DIR)).toEqual({
      reference: 'assets/eyecatch.png',
      absolutePath: path.join(BASE_DIR, 'assets/eyecatch.png'),
    });
  });

  it('featured_imageが無ければundefinedを返す', () => {
    expect(resolveFeaturedImageReference({}, BASE_DIR)).toBeUndefined();
  });

  it('外部URLやdata URIは同梱対象にできないためundefinedを返す', () => {
    expect(resolveFeaturedImageReference({ featured_image: 'https://example.com/a.png' }, BASE_DIR)).toBeUndefined();
    expect(resolveFeaturedImageReference({ featured_image: '//example.com/a.png' }, BASE_DIR)).toBeUndefined();
    expect(resolveFeaturedImageReference({ featured_image: 'data:image/png;base64,AA' }, BASE_DIR)).toBeUndefined();
  });
});

describe('guessImageMimeType', () => {
  it.each([
    ['a.png', 'image/png'],
    ['a.jpg', 'image/jpeg'],
    ['a.JPEG', 'image/jpeg'],
    ['a.gif', 'image/gif'],
    ['a.svg', 'image/svg+xml'],
    ['a.webp', 'image/webp'],
    ['a.bmp', 'image/bmp'],
  ])('%s のMIMEタイプを判定する', (reference, expected) => {
    expect(guessImageMimeType(reference)).toBe(expected);
  });

  it('未知の拡張子はundefinedを返す', () => {
    expect(guessImageMimeType('a.tiff')).toBeUndefined();
    expect(guessImageMimeType('noext')).toBeUndefined();
  });
});

describe('buildArticleFrontMatter', () => {
  const NOW = new Date('2026-06-01T00:00:00Z');

  it('必須項目とstatusの既定値を設定する', () => {
    expect(buildArticleFrontMatter({ title: 'T', slug: 's' }, NOW)).toEqual({
      title: 'T',
      slug: 's',
      status: 'draft',
      publish_scheduled_at: '2026-06-08T00:00:00.000Z',
    });
  });

  it('publish_scheduled_atの既定値は作成時点から7日後(未来日時)にする', () => {
    const frontMatter = buildArticleFrontMatter({ title: 'T', slug: 's' }, NOW);
    expect(validateScheduledPublication(frontMatter.publish_scheduled_at, NOW)).toEqual({
      value: '2026-06-08T00:00:00.000Z',
    });
  });

  it('空のカテゴリ・タグはfront matterへ書き込まない', () => {
    const frontMatter = buildArticleFrontMatter(
      {
        title: 'T',
        slug: 's',
        categories: [],
        tags: [],
      },
      NOW
    );
    expect(frontMatter).not.toHaveProperty('categories');
    expect(frontMatter).not.toHaveProperty('tags');
  });

  it('issue #505: project_id/github_issue_number/github_repositoryは書き込まない', () => {
    const frontMatter = buildArticleFrontMatter(
      {
        title: 'T',
        slug: 's',
        categories: ['技術'],
        tags: ['docker'],
        status: 'publish',
      },
      NOW
    );
    expect(frontMatter).toEqual({
      title: 'T',
      slug: 's',
      status: 'publish',
      categories: ['技術'],
      tags: ['docker'],
      publish_scheduled_at: '2026-06-08T00:00:00.000Z',
    });
    expect(frontMatter).not.toHaveProperty('project_id');
    expect(frontMatter).not.toHaveProperty('github_issue_number');
    expect(frontMatter).not.toHaveProperty('github_repository');
  });

  it('生成したfront matterはそのまま記事として書き出せる', () => {
    const frontMatter = buildArticleFrontMatter({ title: 'タイトル', slug: 'my-slug' }, NOW);
    const reparsed = parseArticle(stringifyArticle({ data: frontMatter, content: '本文' }));
    expect(reparsed.data.title).toBe('タイトル');
    expect(reparsed.data.publish_scheduled_at).toBe('2026-06-08T00:00:00.000Z');
  });
});

describe('suggestSlugFromTitle', () => {
  it.each([
    ['Getting Started with Docker', 'getting-started-with-docker'],
    ['  Hello   World  ', 'hello-world'],
    ['Node.js 18 の新機能', 'node-js-18'],
    ['C++ & Rust: A Comparison', 'c-rust-a-comparison'],
    ['already-a-slug', 'already-a-slug'],
  ])('%s -> %s', (title, expected) => {
    expect(suggestSlugFromTitle(title)).toBe(expected);
  });

  it('英数字を含まないタイトルでは空文字を返す(利用者に入力を促す)', () => {
    expect(suggestSlugFromTitle('日本語のみのタイトル')).toBe('');
  });
});

describe('validateScheduledPublication', () => {
  const NOW = new Date('2026-06-01T00:00:00Z');

  it('未設定の場合は値もエラーも返さない', () => {
    expect(validateScheduledPublication(undefined, NOW)).toEqual({});
    expect(validateScheduledPublication(null, NOW)).toEqual({});
    expect(validateScheduledPublication('', NOW)).toEqual({});
  });

  it('未来のUTC日時を受け付ける', () => {
    expect(validateScheduledPublication('2026-12-25T09:00:00Z', NOW)).toEqual({
      value: '2026-12-25T09:00:00Z',
    });
  });

  it('オフセット付きの日時も受け付ける', () => {
    expect(validateScheduledPublication('2026-12-25T18:00:00+09:00', NOW)).toEqual({
      value: '2026-12-25T18:00:00+09:00',
    });
  });

  it('過去の日時は拒否する', () => {
    const result = validateScheduledPublication('2020-01-01T00:00:00Z', NOW);
    expect(result.value).toBeUndefined();
    expect(result.error).toContain('未来の日時');
  });

  it('現在時刻ちょうどは拒否する(予約にならないため)', () => {
    const result = validateScheduledPublication('2026-06-01T00:00:00Z', NOW);
    expect(result.error).toContain('未来の日時');
  });

  it('タイムゾーンを含まない日時は拒否する', () => {
    const result = validateScheduledPublication('2026-12-25T09:00:00', NOW);
    expect(result.error).toContain('タイムゾーン');
  });

  it('ISO 8601以外の形式は拒否する', () => {
    expect(validateScheduledPublication('2026/12/25 09:00', NOW).error).toContain('ISO 8601');
    expect(validateScheduledPublication('明日', NOW).error).toContain('ISO 8601');
  });

  it('文字列以外は拒否する', () => {
    expect(validateScheduledPublication(12345, NOW).error).toContain('文字列');
  });

  it('前後の空白は取り除いて扱う', () => {
    expect(validateScheduledPublication('  2026-12-25T09:00:00Z  ', NOW)).toEqual({
      value: '2026-12-25T09:00:00Z',
    });
  });

  it('日時として成立しない値は拒否する', () => {
    // 月13は形式(数字2桁)としては通るが、日時としては解釈できない。
    const result = validateScheduledPublication('2026-13-01T09:00:00Z', NOW);
    expect(result.value).toBeUndefined();
    expect(result.error).toContain('解釈できません');
  });

  it('2月30日のような繰り上がる日付は、繰り上がり後の日時として扱われる', () => {
    // JavaScriptのDateは 2026-02-30 を 2026-03-02 として解釈する(不正値にはならない)。
    // 繰り上がり後がNOWより過去のため、過去日時として拒否される。
    const result = validateScheduledPublication('2026-02-30T09:00:00Z', NOW);
    expect(result.error).toContain('未来の日時');
  });

  it('現在時刻を省略した場合は実時刻と比較する', () => {
    const future = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    expect(validateScheduledPublication(future)).toEqual({ value: future });
    expect(validateScheduledPublication('2000-01-01T00:00:00Z').error).toContain('未来の日時');
  });

  it('requireFuture: falseの場合は過去の日時も形式が正しければ受け付ける(issue #520)', () => {
    const result = validateScheduledPublication('2020-01-01T00:00:00Z', NOW, { requireFuture: false });
    expect(result).toEqual({ value: '2020-01-01T00:00:00Z' });
  });

  it('requireFuture: falseでも形式不正は引き続き拒否する', () => {
    const result = validateScheduledPublication('2026/12/25 09:00', NOW, { requireFuture: false });
    expect(result.error).toContain('ISO 8601');
  });
});
