import matter from 'gray-matter';
import * as path from 'path';

export interface LetsBlogFrontMatter {
  title?: string;
  slug?: string;
  site?: string;
  status?: string;
  categories?: string[];
  tags?: string[];
  featured_image?: string;
  wp_post_id?: string | null;
  wp_post_url?: string | null;
  /**
   * 環境(サイトキー)ごとのWordPress投稿ID。ローカル/テスト/本番は別々のWordPressサイトのため、
   * 単一のwp_post_idを使い回すと別サイトの投稿IDで更新しようとして失敗する。
   * 投稿先を都度選べるようになった際に、サイトごとの投稿IDを個別に記録するために追加。
   */
  wp_post_ids?: Record<string, string>;
  github_issue_number?: number;
  github_repository?: string;
  project_id?: number;
  [key: string]: unknown;
}

export interface ParsedArticle {
  data: LetsBlogFrontMatter;
  content: string;
}

export function parseArticle(text: string): ParsedArticle {
  const parsed = matter(text);
  return { data: parsed.data as LetsBlogFrontMatter, content: parsed.content };
}

export function stringifyArticle(article: ParsedArticle): string {
  return matter.stringify(article.content, article.data);
}

export interface LocalImageReference {
  /** Markdown本文中に書かれている参照文字列そのもの(例: "images/eyecatch.png") */
  reference: string;
  /** ディスク上の絶対パス */
  absolutePath: string;
}

const IMAGE_MARKDOWN_PATTERN = /!\[[^\]]*]\(\s*([^)\s]+)[^)]*\)/g;

/**
 * 画像参照をbaseDir配下の絶対パスへ解決する。参照が"/"で始まる場合(例: "/assets/eyecatch.png")、
 * Node標準のpath.resolveはこれをファイルシステム絶対パスとして扱いbaseDirを無視してしまう。
 * このリポジトリの規約上、画像は常に記事ディレクトリの assets/ 配下からの相対パスとして
 * 書かれる想定であり、"/xxx"はサイトルート相対のつもりで書かれたMarkdown相対パスであって
 * OS絶対パスではないため、先頭の"/"を除去してから解決する。
 */
export function resolveLocalImagePath(baseDir: string, reference: string): string {
  const normalized = reference.startsWith('/') ? reference.slice(1) : reference;
  return path.resolve(baseDir, normalized);
}

/**
 * Markdown本文からローカル画像参照(http(s)/dataスキームでないもの)を抽出する。
 * baseDir はMarkdownファイルが置かれているディレクトリ。
 */
export function extractLocalImageReferences(content: string, baseDir: string): LocalImageReference[] {
  const results: LocalImageReference[] = [];
  const seen = new Set<string>();

  for (const match of content.matchAll(IMAGE_MARKDOWN_PATTERN)) {
    const reference = match[1];
    if (/^(https?:)?\/\//.test(reference) || reference.startsWith('data:')) {
      continue;
    }
    if (seen.has(reference)) {
      continue;
    }
    seen.add(reference);
    results.push({ reference, absolutePath: resolveLocalImagePath(baseDir, reference) });
  }

  return results;
}

/**
 * front matterのfeatured_imageをLocalImageReferenceへ変換する。相対パス(例: "assets/eyecatch.png")のみ対象とし、
 * 外部URL(http(s))やdata URIは投稿時のimages同梱対象にできないためundefinedを返す。
 */
export function resolveFeaturedImageReference(
  data: LetsBlogFrontMatter,
  baseDir: string
): LocalImageReference | undefined {
  const reference = data.featured_image;
  if (!reference) {
    return undefined;
  }
  if (/^(https?:)?\/\//.test(reference) || reference.startsWith('data:')) {
    return undefined;
  }
  return { reference, absolutePath: resolveLocalImagePath(baseDir, reference) };
}

/**
 * 投稿先サイト(siteKey)に対応する既存投稿IDを解決する。wp_post_idsに記録があればそれを使う。
 * wp_post_ids導入前に作成された記事(まだこのフィールドを持たない)は、front matterのsiteが
 * 投稿先と一致する場合に限り、従来のwp_post_idを既存投稿として扱う(異なるサイトのIDを
 * 誤って使い回さないよう、一致しない場合は新規投稿として扱う)。
 */
export function resolveExistingPostId(data: LetsBlogFrontMatter, siteKey: string): string | undefined {
  const mapped = data.wp_post_ids?.[siteKey];
  if (mapped) {
    return mapped;
  }
  if (data.site === siteKey && data.wp_post_id != null) {
    return String(data.wp_post_id);
  }
  return undefined;
}
