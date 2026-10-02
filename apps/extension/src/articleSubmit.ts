import * as path from 'path';
import { GitBackend } from './articleGit';
import { parseArticle } from './frontMatter';

/**
 * 記事の提出(issue #1342): 記事ディレクトリをコミットし、現在のブランチをpushし、
 * サーバーの提出APIでPull Requestを作る。
 *
 * このファイルは `vscode` に依存しない。pushの資格情報は利用者のgit設定(またはVSCode本体)に委ね、
 * 拡張はGitHubトークンを持たない。PR作成だけをサーバー経由で行う。
 */

/** 提出APIへ渡す内容。 */
export interface SubmissionRequest {
  headBranch: string;
  githubIssueNumber: number;
  articleSlug: string;
}

export interface SubmissionOutcome {
  prNumber: number;
  url: string;
  created: boolean;
}

export type SubmitResult =
  | ({ status: 'submitted'; branch: string; committed: boolean } & SubmissionOutcome)
  | { status: 'aborted'; reason: string }
  | { status: 'push-failed'; reason: string }
  | { status: 'submission-failed'; reason: string };

export interface SubmitOptions {
  /** ワークスペースのルート(gitリポジトリのルート)。 */
  root: string;
  /** 提出する `articles/<slug>/article.md` の絶対パス。 */
  articlePath: string;
  /** 編集中の記事の本文(front matterを含む)。 */
  text: string;
  backend: GitBackend;
  /** 提出APIを呼ぶ。 */
  createSubmission: (request: SubmissionRequest) => Promise<SubmissionOutcome>;
}

const ARTICLE_BRANCH_PATTERN = /^article\/([1-9]\d*)-(.+)$/;

/** 記事用ブランチ名 `article/<issueNumber>-<slug>` からIssue番号とスラッグを取り出す。 */
export function parseArticleBranch(branch: string): { issueNumber: number; slug: string } | undefined {
  const match = ARTICLE_BRANCH_PATTERN.exec(branch);
  return match ? { issueNumber: Number(match[1]), slug: match[2] } : undefined;
}

/** 提出コミットのメッセージ。記事タイトルとIssue番号を含める。 */
export function buildSubmitCommitMessage(title: string, issueNumber: number): string {
  return `記事を提出する: ${title} (#${issueNumber})`;
}

function nonBlank(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined;
}

function reasonOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 記事を提出する。確認に通らなければ何もコミットもpushもせず、理由を返す。
 * 記事ディレクトリに未コミットの変更が無くても、push済みであればPR作成まで進める。
 */
export async function submitArticle(options: SubmitOptions): Promise<SubmitResult> {
  const { backend } = options;

  const { data } = parseArticle(options.text);
  const title = nonBlank(data.title);
  const slug = nonBlank(data.slug);
  const missing = [title ? undefined : 'title', slug ? undefined : 'slug'].filter(Boolean);
  if (!title || !slug) {
    return {
      status: 'aborted',
      reason: `front matter に ${missing.join(' と ')} がありません。記入してから提出してください。`,
    };
  }

  const segments = path.relative(options.root, options.articlePath).split(path.sep);
  if (segments.length !== 3 || segments[0] !== 'articles' || segments[2] !== 'article.md') {
    return {
      status: 'aborted',
      reason: 'articles/<slug>/article.md を開いた状態で実行してください。',
    };
  }
  const articleDir = `articles/${segments[1]}`;

  if (!(await backend.isRepository())) {
    return { status: 'aborted', reason: 'ワークスペースがgitリポジトリではありません。提出できません。' };
  }

  const branch = await backend.currentBranch();
  const parsed = parseArticleBranch(branch);
  if (!parsed) {
    return {
      status: 'aborted',
      reason: `現在のブランチ ${branch || '(なし)'} は記事用ブランチ(article/<Issue番号>-<スラッグ>)ではありません。`,
    };
  }

  if (await backend.hasStagedOutside([articleDir])) {
    return {
      status: 'aborted',
      reason: `${articleDir}/ の外にステージ済みの変更があります。コミットへ巻き込まないよう、ステージを解除してから実行してください。`,
    };
  }

  const committed = await backend.hasChangesIn([articleDir]);
  if (committed) {
    await backend.commit([articleDir], buildSubmitCommitMessage(title, parsed.issueNumber));
  }

  try {
    await backend.push(branch);
  } catch (error) {
    return { status: 'push-failed', reason: `push に失敗しました: ${reasonOf(error)}` };
  }

  try {
    const outcome = await options.createSubmission({
      headBranch: branch,
      githubIssueNumber: parsed.issueNumber,
      articleSlug: slug,
    });
    return { status: 'submitted', branch, committed, ...outcome };
  } catch (error) {
    return {
      status: 'submission-failed',
      reason: `push は完了しましたが、Pull Request の作成に失敗しました: ${reasonOf(error)}`,
    };
  }
}
