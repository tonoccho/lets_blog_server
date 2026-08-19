import matter from 'gray-matter';
import * as path from 'path';

/** article.md のfront matterで扱う項目。未知のキーはそのまま保持する。 */
export interface LetsBlogFrontMatter {
  title?: string;
  slug?: string;
  site?: string;
  status?: string;
  categories?: string[];
  tags?: string[];
  featured_image?: string;
  /**
   * 環境(サイトキー)ごとのWordPress投稿ID。issue #505以降、新規作成・投稿では書き込まなくなった
   * (DB(postsテーブル)側の情報をサーバーAPI経由で参照する、resolveExistingPostId/apiClient.lookupExistingPost
   * を参照)。既にこのフィールドを持つ古いarticle.mdとの後方互換のため、読み込みは引き続きサポートする。
   */
  wp_post_ids?: Record<string, string>;
  /**
   * 公開予定日時(ISO 8601)。本番(live)サイトへの投稿時のみ有効で、
   * サーバー側でWordPressの予約投稿(status=future)として扱われる。
   */
  publish_scheduled_at?: string;
  /**
   * issue #505以降、新規作成では書き込まなくなった(GitHub Issueとの紐付けはArticlePlanSessionで
   * サーバー側管理)。古いarticle.mdとの後方互換のため型としては残す。
   */
  github_issue_number?: number;
  github_repository?: string;
  /**
   * issue #505以降、新規作成では書き込まなくなった(プロジェクトはワークスペース単位の選択
   * (config.getProjectId)で解決する)。古いarticle.mdとの後方互換のため型としては残す。
   */
  project_id?: number;
  [key: string]: unknown;
}

/** front matterと本文へ分離した記事。 */
export interface ParsedArticle {
  data: LetsBlogFrontMatter;
  content: string;
}

/** 記事テキストをfront matterと本文へ分離する。front matterが無い場合dataは空になる。 */
export function parseArticle(text: string): ParsedArticle {
  const parsed = matter(text);
  return { data: parsed.data as LetsBlogFrontMatter, content: parsed.content };
}

/** front matterと本文を1つの記事テキストへ戻す。 */
export function stringifyArticle(article: ParsedArticle): string {
  return matter.stringify(article.content, article.data);
}

/** 本文中のローカル画像参照と、その実ファイルの位置。 */
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
 * 記録が無い場合(issue #505以降の新規記事、またはwp_post_ids導入前の古い記事)は、
 * 呼び出し元がapiClient.lookupExistingPost経由でDB側の情報を照会する。
 */
export function resolveExistingPostId(data: LetsBlogFrontMatter, siteKey: string): string | undefined {
  return data.wp_post_ids?.[siteKey];
}

/** 新規記事のfront matterを組み立てるための入力。 */
export interface ArticleFrontMatterInput {
  title: string;
  slug: string;
  categories?: string[];
  tags?: string[];
  status?: string;
}

/** publish_scheduled_atの既定値に使う、作成日からのオフセット(日数)。 */
const DEFAULT_SCHEDULED_PUBLICATION_OFFSET_DAYS = 7;

/**
 * 新規記事のfront matterを組み立てる。
 *
 * GitHub Issue起点(Article Plan)とコマンド起点(Create Article)で
 * 同じ項目・同じ既定値になるよう、生成をこの関数へ集約する。
 * 値が無い項目は省略し、front matterに空の項目が並ばないようにする。
 *
 * publish_scheduled_atは作成時点から7日後を既定値とする。validateScheduledPublicationが
 * 未来日時のみを許可するため、作成直後の当日日付など短すぎる既定値では、公開先サイトを問わず
 * 投稿処理そのものがすぐに失敗するようになってしまう。
 */
export function buildArticleFrontMatter(
  input: ArticleFrontMatterInput,
  now: Date = new Date()
): LetsBlogFrontMatter {
  const scheduledAt = new Date(now.getTime() + DEFAULT_SCHEDULED_PUBLICATION_OFFSET_DAYS * 24 * 60 * 60 * 1000);
  const frontMatter: LetsBlogFrontMatter = {
    title: input.title,
    slug: input.slug,
    status: input.status ?? 'draft',
    publish_scheduled_at: scheduledAt.toISOString(),
  };
  if (input.categories && input.categories.length > 0) {
    frontMatter.categories = input.categories;
  }
  if (input.tags && input.tags.length > 0) {
    frontMatter.tags = input.tags;
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

/** 公開予定日時の検証結果。値とエラーは排他。 */
export interface ScheduledPublicationValidation {
  /** 検証を通ったISO 8601文字列。未設定または不正な場合はundefined。 */
  value?: string;
  /** 不正だった場合の理由。 */
  error?: string;
}

/**
 * front matterのpublish_scheduled_atを検証する。
 *
 * 過去の日時を許可しないのは、投稿しても即時公開扱いになり、利用者の意図
 * (予約したつもり)と結果が食い違うため。サーバー側でも同じ検証を行うが、
 * 送信前に気付ける方が手戻りが少ないため拡張側でも確認する。
 */
export function validateScheduledPublication(
  value: unknown,
  now: Date = new Date()
): ScheduledPublicationValidation {
  if (value == null || value === '') {
    return {};
  }
  if (typeof value !== 'string') {
    return { error: 'publish_scheduled_at は文字列(ISO 8601形式)で指定してください。' };
  }
  const trimmed = value.trim();
  // タイムゾーン指定の無い日時は、どの時刻を意図したのか一意に決まらないため受け付けない。
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(\.\d+)?(Z|[+-]\d{2}:\d{2})$/.test(trimmed)) {
    return {
      error:
        'publish_scheduled_at はタイムゾーンを含むISO 8601形式で指定してください(例: 2026-12-25T09:00:00Z、2026-12-25T18:00:00+09:00)。',
    };
  }
  const parsed = new Date(trimmed);
  if (Number.isNaN(parsed.getTime())) {
    return { error: `publish_scheduled_at を日時として解釈できません: ${trimmed}` };
  }
  if (parsed.getTime() <= now.getTime()) {
    return { error: `publish_scheduled_at には未来の日時を指定してください: ${trimmed}` };
  }
  return { value: trimmed };
}
