/**
 * @jest-environment node
 */
import { executesSeedStage, shouldCheckSyntheticAccounts } from './acceptance-accounts';
import {
  parseNoDepsFromArgv,
  resolveExecutedProjectsFromArgv,
  type ProjectBrowserSelection,
} from './browser-prerequisite';

/**
 * #1634 QA: `--project=at-main --no-deps` では依存先(at-seed)は実行されない。
 * global-setup が使う argv 経由の解決が `--no-deps` を反映することを検証する。
 */
const PROJECTS: ProjectBrowserSelection[] = [
  { name: 'at-setup' },
  { name: 'at-seed', dependencies: ['at-setup'] },
  { name: 'at-provision', dependencies: ['at-seed'] },
  { name: 'at-main', dependencies: ['at-provision'] },
];
const names = (projects: ProjectBrowserSelection[]) => projects.map((p) => p.name);

describe('parseNoDepsFromArgv', () => {
  it.each([
    [['node', 'playwright', 'test', '--project=at-main', '--no-deps'], true],
    [['node', 'playwright', 'test', '--no-deps', '--project', 'at-main'], true],
    [['node', 'playwright', 'test', '--project=at-main'], false],
    [['node', 'playwright', 'test', '--grep', '--no-deps-note'], false],
  ])('%j -> %s', (argv, expected) => {
    expect(parseNoDepsFromArgv(argv)).toBe(expected);
  });
});

describe('resolveExecutedProjectsFromArgv', () => {
  it('--no-deps では選択した段階だけを実行集合にし、at-seed を含めない', () => {
    const executed = resolveExecutedProjectsFromArgv(PROJECTS, ['--project=at-main', '--no-deps']);
    expect(names(executed)).toEqual(['at-main']);
    expect(executesSeedStage(executed)).toBe(false);
    expect(shouldCheckSyntheticAccounts({ executesSeed: executesSeedStage(executed), needsSetup: false })).toBe(true);
  });

  it('--no-deps が無ければ依存先を推移的に含み、at-seed を含む', () => {
    const executed = resolveExecutedProjectsFromArgv(PROJECTS, ['--project=at-main']);
    expect(names(executed)).toEqual(['at-setup', 'at-seed', 'at-provision', 'at-main']);
    expect(executesSeedStage(executed)).toBe(true);
  });

  it('--no-deps でも --project 未指定なら全プロジェクト', () => {
    expect(names(resolveExecutedProjectsFromArgv(PROJECTS, ['--no-deps']))).toEqual(names(PROJECTS));
  });

  it('--no-deps で --project を複数指定すると、その段階だけ', () => {
    const executed = resolveExecutedProjectsFromArgv(PROJECTS, ['--project=at-seed', '--project', 'at-main', '--no-deps']);
    expect(names(executed)).toEqual(['at-seed', 'at-main']);
  });
});
