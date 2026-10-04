import { execFile } from 'child_process';
import * as path from 'path';

/**
 * Issue起点の記事作成で、記事用ブランチを切って雛形をコミットする(issue #1335)。
 *
 * このファイルは `vscode` に依存しない。VSCode組み込みGit拡張を使う実装は vscodeGit.ts に置き、
 * ここではgitコマンド実装(CliGitBackend)と、ブランチ作成〜コミットの手順だけを持つ。
 */

/** ブランチ作成・コミットに必要なgit操作。実装は差し替えられる。 */
export interface GitBackend {
  isRepository(): Promise<boolean>;
  /** 未コミットの変更(未追跡ファイルを含む)が無ければtrue。 */
  isClean(): Promise<boolean>;
  /** 新しいブランチの起点にする参照。既定ブランチの最新を指す。 */
  resolveBaseRef(): Promise<string>;
  branchExists(name: string): Promise<boolean>;
  /** prefix で始まるローカルブランチ名(辞書順)。 */
  listBranches(prefix: string): Promise<string[]>;
  /** baseRef から name を作り、チェックアウトする。 */
  createBranch(name: string, baseRef: string): Promise<void>;
  checkout(name: string): Promise<void>;
  /** ブランチを強制削除する(取り消し時に、新規作成した空のブランチを片付けるため)。 */
  deleteBranch(name: string): Promise<void>;
  /** relativePaths だけをステージしてコミットする。 */
  commit(relativePaths: string[], message: string): Promise<void>;
  /** 現在チェックアウトしているブランチ名。 */
  currentBranch(): Promise<string>;
  /** relativePaths 配下に未コミットの変更(未追跡ファイルを含む)があればtrue。それ以外のパスは見ない。 */
  hasChangesIn(relativePaths: string[]): Promise<boolean>;
  /** relativePaths の外にステージ済みの変更があればtrue(コミットへ巻き込まれるため)。 */
  hasStagedOutside(relativePaths: string[]): Promise<boolean>;
  /** ブランチをリモートへ push し、upstream を設定する。資格情報は利用者のgit設定・VSCodeに委ねる。 */
  push(branch: string): Promise<void>;
}

/** push 先のリモート名。 */
export const PUSH_REMOTE = 'origin';

export type GitRunner = (args: string[], cwd: string) => Promise<string>;

const runGit: GitRunner = (args, cwd) =>
  new Promise((resolve, reject) => {
    execFile('git', args, { cwd, encoding: 'utf-8' }, (error, stdout, stderr) => {
      if (error) {
        reject(new Error(`git ${args.join(' ')} に失敗しました: ${stderr.trim() || error.message}`));
        return;
      }
      resolve(stdout.trim());
    });
  });

/** 既定ブランチを推測するときに、ローカルに存在すれば採用する名前(先頭ほど優先)。 */
const CONVENTIONAL_DEFAULT_BRANCHES = ['main', 'master', 'develop'];

export class CliGitBackend implements GitBackend {
  constructor(
    protected readonly root: string,
    private readonly run: GitRunner = runGit
  ) {}

  async isRepository(): Promise<boolean> {
    try {
      return (await this.run(['rev-parse', '--is-inside-work-tree'], this.root)) === 'true';
    } catch {
      return false;
    }
  }

  async isClean(): Promise<boolean> {
    return (await this.run(['status', '--porcelain'], this.root)) === '';
  }

  async resolveBaseRef(): Promise<string> {
    const defaultBranch = await this.resolveDefaultBranch();
    // 最新化はベストエフォート。リモートが無い・オフラインでもローカルの既定ブランチから切れる。
    try {
      await this.run(['fetch', 'origin', defaultBranch], this.root);
    } catch {
      // 続行する。
    }
    const remoteRef = `origin/${defaultBranch}`;
    return (await this.refExists(`refs/remotes/${remoteRef}`)) ? remoteRef : defaultBranch;
  }

  private async resolveDefaultBranch(): Promise<string> {
    try {
      const head = await this.run(['symbolic-ref', '--short', 'refs/remotes/origin/HEAD'], this.root);
      return head.replace(/^origin\//, '');
    } catch {
      // origin/HEAD が無い(リモート無し、または未設定)。
    }
    for (const candidate of CONVENTIONAL_DEFAULT_BRANCHES) {
      if (await this.refExists(`refs/heads/${candidate}`)) return candidate;
    }
    return this.run(['symbolic-ref', '--short', 'HEAD'], this.root);
  }

  private async refExists(ref: string): Promise<boolean> {
    try {
      await this.run(['show-ref', '--verify', '--quiet', ref], this.root);
      return true;
    } catch {
      return false;
    }
  }

  branchExists(name: string): Promise<boolean> {
    return this.refExists(`refs/heads/${name}`);
  }

  async listBranches(prefix: string): Promise<string[]> {
    const out = await this.run(['for-each-ref', '--format=%(refname:short)', `refs/heads/${prefix}`], this.root);
    return out.split('\n').filter((name) => name !== '');
  }

  async createBranch(name: string, baseRef: string): Promise<void> {
    await this.run(['switch', '-c', name, baseRef], this.root);
  }

  async checkout(name: string): Promise<void> {
    await this.run(['switch', name], this.root);
  }

  async deleteBranch(name: string): Promise<void> {
    await this.run(['branch', '-D', name], this.root);
  }

  async commit(relativePaths: string[], message: string): Promise<void> {
    await this.run(['add', '--', ...relativePaths], this.root);
    await this.run(['commit', '-m', message, '--', ...relativePaths], this.root);
  }

  currentBranch(): Promise<string> {
    return this.run(['branch', '--show-current'], this.root);
  }

  async hasChangesIn(relativePaths: string[]): Promise<boolean> {
    return (await this.run(['status', '--porcelain', '--', ...relativePaths], this.root)) !== '';
  }

  async hasStagedOutside(relativePaths: string[]): Promise<boolean> {
    const staged = await this.run(['diff', '--cached', '--name-only', '-z'], this.root);
    return staged
      .split('\0')
      .filter((file) => file !== '')
      .some((file) => !relativePaths.some((p) => file === p || file.startsWith(`${p}/`)));
  }

  async push(branch: string): Promise<void> {
    await this.run(['push', '--set-upstream', PUSH_REMOTE, branch], this.root);
  }
}

/** 記事用ブランチ名。Issue番号とスラッグから決定的に決まる。 */
export function buildBranchName(issueNumber: number, slug: string): string {
  return `article/${issueNumber}-${slug}`;
}

/** 雛形コミットのメッセージ。記事タイトルとIssue番号を含める。 */
export function buildCommitMessage(title: string, issueNumber: number): string {
  return `記事の雛形を追加する: ${title} (#${issueNumber})`;
}

/** 生成された記事の位置。createArticleScaffold の戻り値と互換。 */
export interface ScaffoldFiles {
  articleDir: string;
  articlePath: string;
}

export type ExistingBranchChoice = 'switch' | 'abort';

export interface ScaffoldOnBranchOptions<T extends ScaffoldFiles> {
  root: string;
  backend: GitBackend;
  issueNumber: number;
  slug: string;
  title: string;
  /** ファイルを生成する。取り消された場合は undefined を返す。 */
  scaffold: () => Promise<T | undefined>;
  /** 同名ブランチが既にあるとき、切り替えるか中断するかを利用者に選ばせる。 */
  chooseOnExistingBranch: (branch: string) => Promise<ExistingBranchChoice>;
}

export type ScaffoldOnBranchResult<T extends ScaffoldFiles> =
  /** committed が false なら、雛形が既存と同一でコミットする差分が無かった。 */
  | { status: 'created'; scaffold: T; branch: string; switched: boolean; committed: boolean }
  | { status: 'aborted'; reason: string }
  | { status: 'cancelled' };

/**
 * ワークスペースの状態を確認し、記事用ブランチへ移ってから雛形を生成し、雛形だけをコミットする。
 * 確認に通らない場合はファイルもブランチも作らず、理由を返す。
 */
export async function scaffoldArticleOnBranch<T extends ScaffoldFiles>(
  options: ScaffoldOnBranchOptions<T>
): Promise<ScaffoldOnBranchResult<T>> {
  const { backend } = options;
  if (!(await backend.isRepository())) {
    return {
      status: 'aborted',
      reason: 'ワークスペースがgitリポジトリではありません。記事用ブランチを切れないため、記事を作成しませんでした。',
    };
  }
  if (!(await backend.isClean())) {
    return {
      status: 'aborted',
      reason: '未コミットの変更があります。コミットまたは退避してから、もう一度実行してください。',
    };
  }

  const originalBranch = await backend.currentBranch();
  const branch = buildBranchName(options.issueNumber, options.slug);
  const switched = await backend.branchExists(branch);
  if (switched) {
    if ((await options.chooseOnExistingBranch(branch)) !== 'switch') {
      return { status: 'aborted', reason: `ブランチ ${branch} は既に存在します。中断しました。` };
    }
    await backend.checkout(branch);
  } else {
    await backend.createBranch(branch, await backend.resolveBaseRef());
  }

  let scaffold: T | undefined;
  try {
    scaffold = await options.scaffold();
  } catch (error) {
    await rollBack(backend, originalBranch, branch, !switched);
    throw error;
  }
  if (!scaffold) {
    await rollBack(backend, originalBranch, branch, !switched);
    return { status: 'cancelled' };
  }
  const files = [scaffold.articlePath, path.join(scaffold.articleDir, 'assets', '.gitkeep')].map((file) =>
    path.relative(options.root, file).split(path.sep).join('/')
  );
  // 既存記事を同一内容で上書きすると差分が無く、`git commit` が生のエラーで失敗するため、コミットを省く。
  const committed = await backend.hasChangesIn(files);
  if (committed) {
    await backend.commit(files, buildCommitMessage(options.title, options.issueNumber));
  }
  return { status: 'created', scaffold, branch, switched, committed };
}

/**
 * 取り消し・失敗時に、元のブランチへ戻し、この呼び出しで作ったブランチだけを削除する。
 * 巻き戻しの失敗は握りつぶす(呼び出し元が見るべきは元の失敗であり、巻き戻しの失敗で隠してはならない)。
 */
async function rollBack(backend: GitBackend, originalBranch: string, branch: string, created: boolean): Promise<void> {
  try {
    await backend.checkout(originalBranch);
    if (created) {
      await backend.deleteBranch(branch);
    }
  } catch {
    // ベストエフォート。
  }
}
