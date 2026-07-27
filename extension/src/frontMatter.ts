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
