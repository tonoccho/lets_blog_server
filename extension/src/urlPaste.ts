/**
 * URLペースト時のカード形式判定・記法組み立てロジック(Issue #339)。
 * VSCode APIに依存しない純粋な文字列処理のみを担い、extension.ts側で
 * クリップボード読み取り・エディタへの挿入・content-cache APIの呼び出しと組み合わせて使う。
 *
 * - Ctrl+Shift+V: URLに応じて[blogcard]/[amazon]の組み込みタグを挿入する。
 * - Ctrl+V: 通常のMarkdownリンク`[Title | サイト名](URL)`を挿入する。
 */

/** サーバー側(ContentCacheService.resolveType)と同じ判定基準のAmazon固定ホスト。 */
const AMAZON_HOSTS_EXACT = new Set(['amzn.to', 'amzn.asia']);

/**
 * クリップボードのテキストが単一のhttp/https絶対URLかどうかを判定する。
 * 前後の空白は許容するが、URL以外の文字列を含む場合(複数行・通常の文章など)はundefinedを返し、
 * 呼び出し側で既定の貼り付け動作へフォールバックできるようにする。
 */
export function parseHttpUrl(text: string): URL | undefined {
  const trimmed = text.trim();
  if (!trimmed || /\s/.test(trimmed)) {
    return undefined;
  }
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return undefined;
  }
  return url.protocol === 'http:' || url.protocol === 'https:' ? url : undefined;
}

/**
 * Amazon商品ページのURLかどうかを判定する。カードタグ自体はサーバーからの応答を待たずに
 * 即座に挿入したいため、サーバー(ContentCacheService.resolveType)と同じ基準をクライアント側にも
 * 再現している。実際の情報取得(タイトル・価格等)は非同期でキャッシュを温めるのみに使う。
 */
export function isAmazonUrl(url: URL): boolean {
  const host = url.hostname.toLowerCase();
  return host.includes('amazon.') || AMAZON_HOSTS_EXACT.has(host);
}

/** Ctrl+Shift+V: URLに応じた[blogcard]/[amazon]組み込みタグの記法を返す。 */
export function buildSmartCardTag(url: URL): string {
  return isAmazonUrl(url) ? `[amazon ${url.toString()}]` : `[blogcard ${url.toString()}]`;
}

/**
 * Ctrl+V: 通常のMarkdownリンク記法を返す。
 * タイトル・サイト名を取得できなかった場合は、それぞれURL自身・ホスト名にフォールバックする。
 * リンクテキスト中の`[`/`]`はMarkdownのリンク記法自体を壊すため除去する。
 */
export function buildStandardLink(url: URL, title: string | undefined, siteName: string | undefined): string {
  const safeTitle = stripBrackets(title?.trim()) || url.toString();
  const safeSiteName = stripBrackets(siteName?.trim()) || url.hostname;
  return `[${safeTitle} | ${safeSiteName}](${url.toString()})`;
}

function stripBrackets(text: string | undefined): string {
  return (text ?? '').replace(/[[\]]/g, '');
}
