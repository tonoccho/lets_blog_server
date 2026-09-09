import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { lastCreatedWebviewPanel, resetMocks, setWorkspaceFolders } from '../__mocks__/vscode';
import { PlanPanel } from '../planPanel';

jest.mock('../apiClient', () => ({
  getIssueDescription: jest.fn(async () => '本文'),
  assignIssue: jest.fn(async () => undefined),
}));
jest.mock('../config', () => ({
  requireAccessToken: jest.fn(async () => 'token'),
  getActor: jest.fn(async () => ({ id: 1, email: 'a@example.test', name: 'a' })),
  getProjectId: jest.fn(() => 42),
}));

import * as api from '../apiClient';

const assignIssueMock = api.assignIssue as unknown as jest.Mock;

/**
 * PlanPanelの `approveAndScaffold`(issue #1062)。
 *
 * webviews/plan.js 側の入口検証を迂回して不正なスラッグを直接送っても、
 * `createArticleScaffold` 自身の検証で例外が投げられ、ファイルは作られないことを確認する
 * (多層防御)。正常系(regression)も併せて確認する。
 */

const ISSUE = { number: 1, title: 'テストIssue', htmlUrl: 'https://example.test/issues/1', state: 'open' };

let workspaceRoot: string;

function createContext(): unknown {
  return { extensionUri: `file://${path.resolve(__dirname, '..', '..')}` };
}

async function send(message: unknown): Promise<void> {
  lastCreatedWebviewPanel?.webview.postMessageToExtension(message);
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
}

function posted(): { command: string; payload: unknown }[] {
  return (lastCreatedWebviewPanel?.webview.posted ?? []) as { command: string; payload: unknown }[];
}

function articlesDir(): string {
  return path.join(workspaceRoot, 'articles');
}

beforeEach(() => {
  workspaceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-planpanel-'));
  setWorkspaceFolders([{ uri: { fsPath: workspaceRoot } }]);
  assignIssueMock.mockClear();
  PlanPanel.createOrShow(createContext() as never);
});

afterEach(() => {
  lastCreatedWebviewPanel?.fireDispose();
  resetMocks();
  fs.rmSync(workspaceRoot, { recursive: true, force: true });
});

describe('approveAndScaffold', () => {
  it('入口検証を迂回した不正なスラッグは createArticleScaffold の例外で拒否され、何も作られない', async () => {
    await send({
      command: 'approveAndScaffold',
      issue: ISSUE,
      metadata: { title: 'タイトル', slug: '../../../evil', categories: [], tags: [] },
    });

    expect(fs.existsSync(articlesDir())).toBe(false);
    const errors = posted().filter((m) => m.command === 'error');
    expect(errors).toHaveLength(1);
    expect((errors[0].payload as { error: string }).error).toContain(
      'スラッグは半角英数字とハイフンのみで入力してください'
    );
    expect(assignIssueMock).not.toHaveBeenCalled();
  });

  it('正常なスラッグは従来どおりスキャフォールドを生成する(回帰なし)', async () => {
    await send({
      command: 'approveAndScaffold',
      issue: ISSUE,
      metadata: { title: 'タイトル', slug: 'my-article-01', categories: [], tags: [] },
    });

    const articleMd = path.join(articlesDir(), 'my-article-01', 'article.md');
    expect(fs.existsSync(articleMd)).toBe(true);
    expect(posted().some((m) => m.command === 'scaffoldCreated')).toBe(true);
    expect(assignIssueMock).toHaveBeenCalledWith('token', expect.anything(), 42, 1);
  });
});
