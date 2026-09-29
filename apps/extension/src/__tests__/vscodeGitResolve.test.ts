import * as vscode from 'vscode';
import { CliGitBackend } from '../articleGit';
import { resolveGitBackend, VscodeGitBackend } from '../vscodeGit';

type Ext = { isActive: boolean; activate: () => Promise<unknown>; exports: unknown };
const ext = (vscode as unknown as { extensions: { getExtension: jest.Mock } }).extensions;

function withExtension(extension: Ext | undefined): void {
  ext.getExtension.mockReturnValue(extension);
}

describe('resolveGitBackend', () => {
  it('Git拡張が無ければCLI実装へフォールバックする', async () => {
    withExtension(undefined);
    expect(await resolveGitBackend('/tmp/x')).toBeInstanceOf(CliGitBackend);
    expect(await resolveGitBackend('/tmp/x')).not.toBeInstanceOf(VscodeGitBackend);
  });

  it('Git拡張がリポジトリを返せば拡張API実装を使う', async () => {
    const repository = {};
    withExtension({
      isActive: true,
      activate: async () => undefined,
      exports: { getAPI: () => ({ getRepository: () => repository }) },
    });
    expect(await resolveGitBackend('/tmp/x')).toBeInstanceOf(VscodeGitBackend);
  });

  it('未アクティブなGit拡張は有効化してから使う', async () => {
    const activate = jest.fn(async () => ({ getAPI: () => ({ getRepository: () => ({}) }) }));
    withExtension({ isActive: false, activate, exports: undefined });
    expect(await resolveGitBackend('/tmp/x')).toBeInstanceOf(VscodeGitBackend);
    expect(activate).toHaveBeenCalled();
  });

  it('リポジトリが見つからない・APIが例外を投げる場合はCLI実装へフォールバックする', async () => {
    withExtension({
      isActive: true,
      activate: async () => undefined,
      exports: { getAPI: () => ({ getRepository: () => null }) },
    });
    expect(await resolveGitBackend('/tmp/x')).not.toBeInstanceOf(VscodeGitBackend);
    withExtension({
      isActive: true,
      activate: async () => undefined,
      exports: {
        getAPI: () => {
          throw new Error('disabled');
        },
      },
    });
    expect(await resolveGitBackend('/tmp/x')).not.toBeInstanceOf(VscodeGitBackend);
  });
});
