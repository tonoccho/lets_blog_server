import * as path from 'path';
import {
  parseArticle,
  stringifyArticle,
  resolveLocalImagePath,
  extractLocalImageReferences,
  resolveFeaturedImageReference,
  resolveExistingPostId,
  guessImageMimeType,
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

describe('resolveExistingPostId', () => {
  it('wp_post_idsにサイトの記録があればそれを使う', () => {
    const data = { wp_post_ids: { local: '10', production: '20' } };
    expect(resolveExistingPostId(data, 'production')).toBe('20');
  });

  it('wp_post_ids未導入の記事は、siteが一致する場合のみwp_post_idを流用する', () => {
    const data = { site: 'production', wp_post_id: '30' };
    expect(resolveExistingPostId(data, 'production')).toBe('30');
  });

  it('別サイトのIDを誤って使い回さない', () => {
    const data = { site: 'local', wp_post_id: '30' };
    expect(resolveExistingPostId(data, 'production')).toBeUndefined();
  });

  it('どこにも記録が無ければundefinedを返す', () => {
    expect(resolveExistingPostId({}, 'production')).toBeUndefined();
    expect(resolveExistingPostId({ site: 'production', wp_post_id: null }, 'production')).toBeUndefined();
  });

  it('数値で保存されたwp_post_idも文字列として返す', () => {
    expect(resolveExistingPostId({ site: 'local', wp_post_id: 42 as unknown as string }, 'local')).toBe('42');
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
