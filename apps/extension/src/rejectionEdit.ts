import * as path from 'path';
import { GitBackend } from './articleGit';
import { parseArticleBranch } from './articleSubmit';

/**
 * 差し戻された記事を通知から開いて修正する(issue #1348)。
 *
 * このファイルは `vscode` に依存しない。エディタを開く・指摘事項を表示するといったVSCode UIは、
 * 呼び出し側(extension.ts)が関数として渡す。再提出は #1342 の提出処理(articleSubmit.ts)をそのまま使う。
 */

/** 差し戻し通知の選択肢。 */
export const EDIT_ACTION = '編集';

/** 指摘事項を `context.globalState` へ保存するキー。 */
export const FINDINGS_STATE_KEY = 'letsBlog.rejectionFindings';

/** 保持する記事数の上限。古い記事から捨てる。 */
const MAX_FINDINGS = 100;

/** 指摘コメントが無い差し戻しで、代わりに保持する文言。 */
export const NO_COMMENT = '(指摘コメントを取得できません。Pull Requestを確認してください)';

/** 記事のスラッグごとの指摘事項。編集中に何度でも参照できるよう保存する。 */
export class RejectionFindings {
  constructor(
    private readonly state: {
      get: (key: string) => Record<string, string> | undefined;
      update: (key: string, value: Record<string, string>) => PromiseLike<void>;
    }
  ) {}

  get(slug: string): string | undefined {
    return this.state.get(FINDINGS_STATE_KEY)?.[slug];
  }

  async record(slug: string, comment: string): Promise<void> {
    const { [slug]: _replaced, ...others } = this.state.get(FINDINGS_STATE_KEY) ?? {};
    const entries = Object.entries({ ...others, [slug]: comment }).slice(-MAX_FINDINGS);
    await this.state.update(FINDINGS_STATE_KEY, Object.fromEntries(entries));
  }
}

/** `.../articles/<slug>/article.md` からスラッグを取り出す。 */
export function articleSlugOfPath(filePath: string): string | undefined {
  return /(?:^|[\\/])articles[\\/]([^\\/]+)[\\/]article\.md$/.exec(filePath)?.[1];
}

/** 出力チャネルへ書く指摘事項の本文。 */
export function formatFindings(slug: string, comment: string): string {
  return `記事「${slug}」の指摘事項:\n${comment}`;
}

export interface EditOptions {
  /** ワークスペースのルート(gitリポジトリのルート)。 */
  root: string;
  review: { articleSlug: string; rejectComment?: string | null };
  backend: GitBackend;
  findings: RejectionFindings;
  /** `article.md` を通常のエディタで開く。 */
  openArticle: (absolutePath: string) => Promise<void>;
  /** 指摘事項を表示する(VSCode標準のUI)。 */
  showFindings: (slug: string, comment: string) => void;
}

export type EditResult =
  | { status: 'opened'; branch: string; articlePath: string; switched: boolean }
  | { status: 'not-in-workspace' | 'dirty' | 'not-repository'; reason: string };

/**
 * 差し戻された記事のブランチへ切り替え、`article.md` を開き、指摘事項を表示する。
 * 記事がワークスペースに無いとき、未コミットの変更があるときは、切り替えもエディタの起動もしない。
 */
export async function editRejectedArticle(options: EditOptions): Promise<EditResult> {
  const { backend } = options;
  const slug = options.review.articleSlug;

  if (!(await backend.isRepository())) {
    return { status: 'not-repository', reason: 'ワークスペースがgitリポジトリではありません。記事を開けません。' };
  }
  const branch = (await backend.listBranches('article/')).find((name) => parseArticleBranch(name)?.slug === slug);
  if (!branch) {
    return {
      status: 'not-in-workspace',
      reason: `記事「${slug}」のブランチがこのワークスペースにありません。別のマシンで書いた記事は、そのマシンで開いてください。`,
    };
  }

  const switched = (await backend.currentBranch()) !== branch;
  if (switched) {
    if (!(await backend.isClean())) {
      return {
        status: 'dirty',
        reason: '未コミットの変更があるため、記事のブランチへ切り替えませんでした。コミットまたは退避してから、もう一度「編集」を選んでください。',
      };
    }
    await backend.checkout(branch);
  }

  const articlePath = path.join(options.root, 'articles', slug, 'article.md');
  const comment = options.review.rejectComment ?? NO_COMMENT;
  await options.findings.record(slug, comment);
  await options.openArticle(articlePath);
  options.showFindings(slug, comment);
  return { status: 'opened', branch, articlePath, switched };
}
