/**
 * Webview パネルのテストが共通で使う補助(issue #1840)。
 *
 * パネルは実サーバーへの HTTP を挟んで応答するため、Webview へ送られるメッセージを
 * 一定時間までポーリングして待つ。vscode スタブの `lastCreatedWebviewPanel` を読む。
 */
import { lastCreatedWebviewPanel } from '../../__mocks__/vscode';

export type Posted = { command: string; payload: unknown };

export function posted(): Posted[] {
  return (lastCreatedWebviewPanel?.webview.posted ?? []) as Posted[];
}

export function payloadOf(command: string): unknown {
  const found = posted().filter((m) => m.command === command);
  if (found.length === 0) throw new Error(`${command} が送られていません: ${JSON.stringify(posted())}`);
  return found[found.length - 1].payload;
}

/** 条件が成り立つまで待つ(上限 timeoutMs。成り立たなくても例外にせず戻る)。Date は実時間であること。 */
export async function waitFor(condition: () => boolean, timeoutMs = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition() && Date.now() < deadline) {
    // setImmediate で待つ(偽タイマーで setTimeout を止めているテストでも、実 I/O の完了を待てる)。
    await new Promise((resolve) => setImmediate(resolve));
  }
}

/**
 * Webview 側のスクリプトが postMessage した状況を再現し、拡張からの応答
 * (Webview への投稿が増える、またはパネルが閉じる)まで待つ。
 * 応答を返さない経路を試すときは `expectReply: false` にして 1 周だけ待つ。
 */
export async function send(message: unknown, expectReply = true): Promise<void> {
  const panel = lastCreatedWebviewPanel;
  const before = posted().length;
  panel?.webview.postMessageToExtension(message);
  if (expectReply) {
    await waitFor(() => posted().length > before || panel?.disposed === true);
  } else {
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}
