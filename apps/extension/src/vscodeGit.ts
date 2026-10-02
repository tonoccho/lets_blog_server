import * as path from 'path';
import * as vscode from 'vscode';
import { CliGitBackend, GitBackend, PUSH_REMOTE } from './articleGit';

/**
 * VSCode組み込みGit拡張(`vscode.git`)のAPIでgit操作を行う実装(issue #1335)。
 * 資格情報のプロンプトなどをVSCode本体に任せられる。Git拡張が使えない環境では
 * CliGitBackend(`git`コマンド)へフォールバックする。
 */

/** Git拡張の Repository のうち、この実装が使う部分。 */
export interface VscodeGitRepository {
  createBranch(name: string, checkout: boolean, ref?: string): Promise<void>;
  checkout(treeish: string): Promise<void>;
  add(paths: string[]): Promise<void>;
  commit(message: string): Promise<void>;
  /** 古いGit拡張には無い。無ければ `git branch -D` を使う。 */
  deleteBranch?(name: string, force?: boolean): Promise<void>;
  push(remoteName?: string, branchName?: string, setUpstream?: boolean): Promise<void>;
}

interface VscodeGitExports {
  getAPI(version: 1): { getRepository(uri: unknown): VscodeGitRepository | null };
}

/** 状態の問い合わせ(既定ブランチの解決を含む)はCLI実装を引き継ぎ、変更操作だけを拡張APIで行う。 */
export class VscodeGitBackend extends CliGitBackend implements GitBackend {
  constructor(
    root: string,
    private readonly repository: VscodeGitRepository
  ) {
    super(root);
  }

  override createBranch(name: string, baseRef: string): Promise<void> {
    return this.repository.createBranch(name, true, baseRef);
  }

  override checkout(name: string): Promise<void> {
    return this.repository.checkout(name);
  }

  override deleteBranch(name: string): Promise<void> {
    return this.repository.deleteBranch ? this.repository.deleteBranch(name, true) : super.deleteBranch(name);
  }

  override async commit(relativePaths: string[], message: string): Promise<void> {
    await this.repository.add(relativePaths.map((p) => path.join(this.root, p)));
    await this.repository.commit(message);
  }

  /** 資格情報のプロンプトはVSCode本体が扱う。拡張はトークンを持たない。 */
  override push(branch: string): Promise<void> {
    return this.repository.push(PUSH_REMOTE, branch, true);
  }
}

/** 使えるGit拡張のRepositoryがあればそれを、無ければ `git` コマンドの実装を返す。 */
export async function resolveGitBackend(root: string): Promise<GitBackend> {
  try {
    const extension = vscode.extensions.getExtension<VscodeGitExports>('vscode.git');
    if (extension) {
      const exports = extension.isActive ? extension.exports : await extension.activate();
      const repository = exports.getAPI(1).getRepository(vscode.Uri.file(root));
      if (repository) {
        return new VscodeGitBackend(root, repository);
      }
    }
  } catch {
    // Git拡張が無効など。gitコマンドへフォールバックする。
  }
  return new CliGitBackend(root);
}
