import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { LetsBlogFrontMatter, stringifyArticle } from './frontMatter';

/**
 * 記事ディレクトリ(articles/<slug>/)の生成をまとめる。
 *
 * GitHub Issue起点(PlanPanel)とコマンド起点(ArticleCreationPanel)の双方から
 * 同じ配置・同じfront matterで記事を作れるよう、生成処理をここへ集約する。
 */

export interface ArticleScaffoldOptions {
  /** ワークスペースのルート。articles/ はこの直下に作る。 */
  workspaceRoot: string;
  /** 記事ディレクトリ名になるスラッグ。 */
  slug: string;
  /** article.md へ書き込むfront matter。 */
  frontMatter: LetsBlogFrontMatter;
  /** 本文。空の場合は案内文を入れる。 */
  content: string;
}

export interface ArticleScaffoldResult {
  /** 生成した article.md の絶対パス。 */
  articlePath: string;
  /** 記事ディレクトリの絶対パス。 */
  articleDir: string;
}

const PLACEHOLDER_CONTENT = '記事本文をここに記入してください。';

/**
 * 記事ディレクトリと article.md、画像置き場の assets/ を生成する。
 * 既存ディレクトリがある場合は上書き確認を行い、拒否された場合はundefinedを返す。
 */
export async function createArticleScaffold(
  options: ArticleScaffoldOptions
): Promise<ArticleScaffoldResult | undefined> {
  const articleDir = path.join(options.workspaceRoot, 'articles', options.slug);

  if (fs.existsSync(articleDir)) {
    const overwrite = await vscode.window.showWarningMessage(
      `articles/${options.slug} は既に存在します。上書きしますか?`,
      'Yes',
      'No'
    );
    if (overwrite !== 'Yes') {
      return undefined;
    }
  }

  fs.mkdirSync(articleDir, { recursive: true });
  // 画像はこのディレクトリからの相対パスで参照する規約のため、空でも作っておく。
  fs.mkdirSync(path.join(articleDir, 'assets'), { recursive: true });
  fs.writeFileSync(path.join(articleDir, 'assets', '.gitkeep'), '');

  const content = options.content.trim().length > 0 ? options.content : PLACEHOLDER_CONTENT;
  const articlePath = path.join(articleDir, 'article.md');
  fs.writeFileSync(articlePath, stringifyArticle({ data: options.frontMatter, content }), 'utf-8');

  return { articlePath, articleDir };
}

/** 生成した記事をエディタで開く。 */
export async function openArticle(articlePath: string): Promise<void> {
  const doc = await vscode.workspace.openTextDocument(articlePath);
  await vscode.window.showTextDocument(doc);
}

/** ワークスペースが開かれていることを確認して、そのルートパスを返す。 */
export function requireWorkspaceRoot(): string {
  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  if (!workspaceFolder) {
    throw new Error('ワークスペースフォルダが開かれていません。記事を作成するフォルダを開いてください。');
  }
  return workspaceFolder.uri.fsPath;
}
