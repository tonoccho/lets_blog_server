import { expect, type Locator } from '@playwright/test';

/**
 * issue #1381: `page.goto` / `page.reload` 直後にサーバ描画済みのボタンをクリックする
 * ステップ定義向けの共有ヘルパー。
 *
 * ## 背景
 *
 * Next.jsの画面はサーバでHTMLを描画してから、ブラウザ側でReactのハイドレーションが
 * 完了して初めて`onClick`が結びつく。Playwrightのactionabilityチェック
 * (visible / stable / enabled / receives events)はDOM要素の見た目だけを見るため、
 * サーバ描画済みのボタンはハイドレーション前でも全て通ってしまい、`click()`自体は
 * 成功したように見えるが、実際には何も起こらない。Next.jsはハイドレーション完了を
 * 検出できる標識を公開していないため、標識を待つのではなく「期待した結果が出るまで
 * クリックし直す」方針を取る(#1381。同根の問題は#1360)。
 *
 * ## 使ってよい場面 / 使ってはいけない場面(必読)
 *
 * **べき等な操作(タブを開く、メニューを開くなど)にのみ使うこと。** このヘルパーは
 * 1回の呼び出しで`trigger.click()`を複数回発火しうる。「保存」「削除」「送信」の
 * ような副作用のある操作に使うと、ハイドレーション前の空振りだと思って再試行した
 * クリックが実は届いていて、操作が二重に実行されてしまう。
 *
 * **開いている状態で再度クリックすると閉じてしまうトグルに使う場合は注意が必要。**
 * 1回目のクリックが実際に効いていれば`expected`の出現待ち(`visibleTimeoutMs`)が
 * その場で解決し、2回目のクリックは発生しないため安全。危険が残るのは、1回目の
 * クリックが効いた直後、Reactの再描画がまだコミットされていないタイミングで
 * `visibleTimeoutMs`が尽きてしまった場合で、その場合は「既に開いている」状態へ
 * 2回目のクリックを送ってしまい、トグルを閉じる方向に振れる。下記の既定値は、
 * この空振りを避けられる程度の余裕を持たせてあるが、ゼロにはできない。トグルへ
 * 適用する場合は、この残余リスクを踏まえること。
 *
 * ## タイムアウトの既定値
 *
 * - {@link DEFAULT_VISIBLE_TIMEOUT_MS}: 1回のクリック試行につき、`expected`の
 *   出現を待つ時間。ハイドレーション前でクリックが全く効いていない場合、
 *   どれだけ待っても`expected`は現れない(#1381本文の実測: `identity/`全体を
 *   既定の並列度で連続実行した際、1回失敗したシナリオは90秒の全体予算を使い
 *   切るまで回復しなかった)。つまりこの待ちが救えるのは「クリックは効いたが、
 *   負荷でReactの再描画コミットが遅延している」ケースだけであり、大きくする
 *   理由は無い。通常のUI操作の反映として妥当な数秒程度に留める。
 * - {@link DEFAULT_RETRY_TIMEOUT_MS}: 再試行ループ全体の予算。シナリオ全体の
 *   予算(90秒、#1376)に対して十分小さく取り、単独実行時(通常は1回目の
 *   クリックで即座に成功する)の所要時間を実質的に悪化させないようにする。
 */
export const DEFAULT_VISIBLE_TIMEOUT_MS = 3_000;
export const DEFAULT_RETRY_TIMEOUT_MS = 10_000;

export interface RetryUntilPassOptions {
  /** 再試行ループ全体のタイムアウト(ms)。省略時は{@link DEFAULT_RETRY_TIMEOUT_MS}。 */
  timeoutMs?: number;
}

/**
 * `action`が例外を投げなくなるまで再試行する。Playwrightの`expect(...).toPass()`の
 * 薄いラッパー(#1381)。
 *
 * `clickUntilVisible`の再試行ループ本体をここへ切り出してあるのは、Playwrightの
 * `Locator`(実ブラウザ接続を要する)を使わずに、任意の非同期関数を渡す単体テストで
 * 再試行・タイムアウトの挙動を検証できるようにするため。
 */
export async function retryUntilPass(action: () => Promise<void>, options?: RetryUntilPassOptions): Promise<void> {
  await expect(action).toPass({ timeout: options?.timeoutMs ?? DEFAULT_RETRY_TIMEOUT_MS });
}

export interface ClickUntilVisibleOptions extends RetryUntilPassOptions {
  /** 1回のクリック試行につき、`expected`の出現を待つ時間(ms)。省略時は{@link DEFAULT_VISIBLE_TIMEOUT_MS}。 */
  visibleTimeoutMs?: number;
}

/**
 * `trigger`をクリックし、`expected`が見えるまでクリックをやり直す(#1381)。
 *
 * 【制約】このファイル冒頭の「使ってよい場面 / 使ってはいけない場面」を必ず読むこと。
 * べき等な操作にのみ使うこと。
 */
export async function clickUntilVisible(
  trigger: Locator,
  expected: Locator,
  options?: ClickUntilVisibleOptions
): Promise<void> {
  await retryUntilPass(async () => {
    await trigger.click();
    await expected.waitFor({ state: 'visible', timeout: options?.visibleTimeoutMs ?? DEFAULT_VISIBLE_TIMEOUT_MS });
  }, options);
}
