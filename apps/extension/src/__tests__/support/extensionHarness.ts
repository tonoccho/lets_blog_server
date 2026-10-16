/**
 * extension.ts の `activate` を、実サーバー(外部通信の代役)と VS Code の代替の上で動かす補助(issue #1840)。
 *
 * - 外部通信: `LocalHttpServer`(gateway・Keycloak の代役)。`api(table)` で応答を決める。
 * - 実行基盤: `vscode` スタブ(コマンド登録・イベント・QuickPick・入力欄・通知・アクティブエディタ)と
 *   `createContext()`(SecretStorage・Memento の代替)。
 * - 実物: config.ts・apiClient.ts・各 Panel・ProofreadController・articleScaffold.ts・git など、拡張自身のモジュール。
 */
import { activate } from '../../extension';
import {
  disposeAllWebviewPanels,
  resetMocks,
  runRegisteredCommand,
  setActiveTextEditor,
  setConfiguration,
} from '../../__mocks__/vscode';
import * as api from '../../apiClient';
import { LocalHttpServer, Reply, RecordedRequest, routes } from './localHttpServer';
import { TestContext, createContext, loginAs, selectProject } from './extensionContext';
import { FakeEditor } from './fakeEditor';

export interface Harness {
  server: LocalHttpServer;
  context: TestContext;
  /** 利用者がコマンドを実行した状況(登録済みコマンドを呼ぶ)。 */
  run: (command: string, ...args: unknown[]) => Promise<unknown>;
  /** サーバーの応答を `METHOD /path` の表で決める。表に無い要求は 404。 */
  api: (table: Record<string, Reply | ((request: RecordedRequest) => Reply | Promise<Reply>)>) => void;
  /** アクティブなエディタを差し替える。 */
  open: (editor: FakeEditor | undefined) => FakeEditor | undefined;
  /** サーバーが受けた要求のうち、`METHOD /path` に一致するもの(クエリは無視)。 */
  requests: (key: string) => RecordedRequest[];
  /** 拡張を無効化する(購読の破棄)とサーバーの停止。 */
  stop: () => Promise<void>;
}

export interface HarnessOptions {
  /** true なら activate の前にログイン・プロジェクト選択を済ませる(起動時の差し戻し確認が走る)。 */
  loggedInAtActivation?: boolean;
  projectId?: number | null;
  login?: boolean;
  /** activate の時点でアクティブなエディタ。 */
  activeEditor?: FakeEditor;
  /** activate の直前に呼ぶ(起動時の確認が呼ぶサーバーの応答を決めるため)。 */
  beforeActivate?: (harness: Harness) => void;
}

export async function startExtension(options: HarnessOptions = {}): Promise<Harness> {
  resetMocks();
  api.clearResponseCache();
  const server = await LocalHttpServer.start();
  setConfiguration('letsBlog.serverUrl', server.url);
  setConfiguration('letsBlog.allowInsecureTls', false);
  const context = createContext();
  const projectId = options.projectId === undefined ? 7 : options.projectId;
  const prepare = async (): Promise<void> => {
    if (options.login !== false) await loginAs(context);
    if (projectId !== null) await selectProject(context, projectId);
  };
  if (options.loggedInAtActivation) await prepare();
  server.respondWith(routes({}));
  if (options.activeEditor) setActiveTextEditor(options.activeEditor);
  const harness: Harness = {
    server,
    context,
    run: (command, ...args) => runRegisteredCommand(command, ...args),
    api: (table) => server.respondWith(routes(table)),
    open: (editor) => {
      setActiveTextEditor(editor);
      return editor;
    },
    requests: (key) => server.recorded.filter((r) => `${r.method} ${r.path.split('?')[0]}` === key),
    stop: async () => {
      // 起動したままの通信(貼り付け時の先行取得など)が終わる前に止めると、通信の失敗として再試行される。
      await server.whenIdle();
      for (const subscription of context.subscriptions) {
        (subscription as { dispose: () => void }).dispose();
      }
      setActiveTextEditor(undefined);
      disposeAllWebviewPanels();
      resetMocks();
      await server.stop();
    },
  };
  options.beforeActivate?.(harness);
  activate(context);
  if (!options.loggedInAtActivation) await prepare();
  return harness;
}
