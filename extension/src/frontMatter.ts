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
    results.push({ reference, absolutePath: path.resolve(baseDir, reference) });
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
  return { reference, absolutePath: path.resolve(baseDir, reference) };
}
