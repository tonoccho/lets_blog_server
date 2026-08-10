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

const IMAGE_MIME_TYPES: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.bmp': 'image/bmp',
};

/**
 * 画像参照の拡張子からMIMEタイプを推定する。判定できない場合はundefinedを返す
 * (プレビューのデータURI化では対象外とし、アップロード時は汎用のバイナリ種別へフォールバックする)。
 */
export function guessImageMimeType(reference: string): string | undefined {
  return IMAGE_MIME_TYPES[path.extname(reference).toLowerCase()];
}

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

export interface ArticleFrontMatterInput {
  title: string;
  slug: string;
  projectId: number;
  categories?: string[];
  tags?: string[];
  status?: string;
  /** GitHub Issue起点で作成した場合のIssue番号。 */
  githubIssueNumber?: number;
  /** GitHub Issue起点で作成した場合のリポジトリURL。 */
  githubRepository?: string;
}

/**
 * 新規記事のfront matterを組み立てる。
 *
 * GitHub Issue起点(Article Plan)とコマンド起点(Create Article)で
 * 同じ項目・同じ既定値になるよう、生成をこの関数へ集約する。
 * 値が無い項目は省略し、front matterに空の項目が並ばないようにする。
 */
export function buildArticleFrontMatter(input: ArticleFrontMatterInput): LetsBlogFrontMatter {
  const frontMatter: LetsBlogFrontMatter = {
    title: input.title,
    slug: input.slug,
    status: input.status ?? 'draft',
    project_id: input.projectId,
  };
  if (input.categories && input.categories.length > 0) {
    frontMatter.categories = input.categories;
  }
  if (input.tags && input.tags.length > 0) {
    frontMatter.tags = input.tags;
  }
  if (input.githubIssueNumber != null) {
    frontMatter.github_issue_number = input.githubIssueNumber;
  }
  if (input.githubRepository) {
    frontMatter.github_repository = input.githubRepository;
  }
  return frontMatter;
}

/**
 * タイトルからスラッグの候補を作る。英数字とハイフンのみを残し、
 * 日本語など変換できない文字しか残らない場合は空文字を返す(利用者に入力を促す)。
 */
export function suggestSlugFromTitle(title: string): string {
  return title
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, ' ')
    .trim()
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '');
}
