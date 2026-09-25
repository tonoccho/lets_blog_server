import { buildSmartCardTag, buildStandardLink, isAmazonUrl, parseHttpUrl } from '../urlPaste';

describe('parseHttpUrl', () => {
  it('http/httpsの絶対URLを解釈する', () => {
    expect(parseHttpUrl('https://example.com/foo')?.toString()).toBe('https://example.com/foo');
    expect(parseHttpUrl('http://example.com')?.toString()).toBe('http://example.com/');
  });

  it('前後の空白は許容する', () => {
    expect(parseHttpUrl('  https://example.com/foo  ')?.toString()).toBe('https://example.com/foo');
  });

  it('URL以外の文字列はundefinedを返す', () => {
    expect(parseHttpUrl('通常の文章です')).toBeUndefined();
    expect(parseHttpUrl('')).toBeUndefined();
  });

  it('URLと他のテキストが混在する場合はundefinedを返す(通常の貼り付けにフォールバックさせるため)', () => {
    expect(parseHttpUrl('見て https://example.com/foo')).toBeUndefined();
    expect(parseHttpUrl('https://example.com/foo\n続きの行')).toBeUndefined();
  });

  it('http/https以外のスキームはundefinedを返す', () => {
    expect(parseHttpUrl('ftp://example.com/foo')).toBeUndefined();
    expect(parseHttpUrl('mailto:foo@example.com')).toBeUndefined();
  });
});

describe('isAmazonUrl', () => {
  it('amazon.*ドメインをAmazon URLと判定する', () => {
    expect(isAmazonUrl(new URL('https://www.amazon.co.jp/dp/B0123456'))).toBe(true);
    expect(isAmazonUrl(new URL('https://amazon.com/dp/B0123456'))).toBe(true);
  });

  it('短縮URL(amzn.to/amzn.asia)をAmazon URLと判定する', () => {
    expect(isAmazonUrl(new URL('https://amzn.to/abc123'))).toBe(true);
    expect(isAmazonUrl(new URL('https://amzn.asia/abc123'))).toBe(true);
  });

  it('Amazon以外のURLはfalseを返す', () => {
    expect(isAmazonUrl(new URL('https://example.com/product'))).toBe(false);
    expect(isAmazonUrl(new URL('https://amazonfakesite.com'))).toBe(false);
  });
});

describe('buildSmartCardTag', () => {
  it('Amazon URLは[amazon]タグを返す', () => {
    expect(buildSmartCardTag(new URL('https://www.amazon.co.jp/dp/B0123456'))).toBe(
      '[amazon https://www.amazon.co.jp/dp/B0123456]'
    );
  });

  it('それ以外のURLは[blogcard]タグを返す', () => {
    expect(buildSmartCardTag(new URL('https://example.com/article'))).toBe(
      '[blogcard https://example.com/article]'
    );
  });
});

describe('buildStandardLink', () => {
  it('タイトル・サイト名を埋め込んだMarkdownリンクを返す', () => {
    const url = new URL('https://example.com/article');
    expect(buildStandardLink(url, '記事タイトル', 'サイト名')).toBe(
      '[記事タイトル | サイト名](https://example.com/article)'
    );
  });

  it('タイトル未取得時はURLそのものをタイトルとして使う', () => {
    const url = new URL('https://example.com/article');
    expect(buildStandardLink(url, undefined, 'サイト名')).toBe(
      '[https://example.com/article | サイト名](https://example.com/article)'
    );
  });

  it('サイト名未取得時はホスト名を使う', () => {
    const url = new URL('https://example.com/article');
    expect(buildStandardLink(url, 'タイトル', undefined)).toBe(
      '[タイトル | example.com](https://example.com/article)'
    );
  });

  it('タイトル・サイト名に含まれる角括弧はMarkdownリンク記法を壊さないよう除去する', () => {
    const url = new URL('https://example.com/article');
    expect(buildStandardLink(url, '[速報]タイトル', 'サイト[名]')).toBe(
      '[速報タイトル | サイト名](https://example.com/article)'
    );
  });
});
