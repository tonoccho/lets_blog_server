import { expect, type Dialog, type Locator, type Page } from '@playwright/test';

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
 * 2回目のクリックを送ってしまい、トグルを閉じる方向に振れる。
 *
 * **`expected`には、`trigger`の操作によってはじめて見えるようになる要素を渡すこと。**
 * `trigger`と無関係に常時表示されている要素(あるいはロケータが緩くて別の要素にも
 * マッチしてしまうもの)を渡すと、2回目以降の試行で必ず「既に目的の状態」と判定され、
 * それ以降クリックが一切飛ばなくなる。現在の呼び出し箇所はどちらもこの前提を満たして
 * いる —— `timezoneOverride.steps.ts`の`[data-testid="timezone-select"]`は「個人設定」
 * タブを開くまで描画されず(`Tabs`は活性タブの内容だけを描画する)、
 * `uiQuality.steps.ts`の`role="dialog"`ドロワーは`HeaderNav`が`isOpen`のときだけ
 * 条件描画する。
 *
 * issue #1360で、この残存リスクを緩和するため、**2回目以降の試行に限り**、クリックの
 * 直前に`expected`が既に見えていないかを確認し、見えていればクリック自体をスキップ
 * するようにした(`isVisible()`は要素が存在しない場合に例外を投げうるため、その場合は
 * false扱いにしている)。1回目のクリックが効いていたのに`visibleTimeoutMs`超過で失敗と
 * 誤判定されたケースでも、再試行時点までに描画が追いついていれば2回目のクリックを
 * 送らずに済む。これは「状態は変わったがまだ描画されていない」という極狭の窓
 * (DOMを読む限り原理的に見分けが付かない)までは消せないが、実際にトグルが踏むで
 * あろう「既に見えて開いているのに余計な再試行のクリックが飛ぶ」ケースは防げる。
 *
 * **この確認を1回目に効かせてはいけない。** 1回目にも効かせると、`expected`が入室時点で
 * 既に見えている場合にクリックが**一度も行われない**まま成功扱いになる。例えば将来の
 * 退行でドロワーが初期状態から開いていたり無条件描画になったりすると、開くボタンが
 * 完全に壊れていてもシナリオが通ってしまう。それは受け入れテストが検出すべき欠陥
 * そのものなので、1回目は必ずクリックする(#1360のレビュー指摘)。
 *
 * べき等な操作(タブ切り替えなど)に対しても、2回目以降のこの確認は無駄なクリックを
 * 減らす方向にしか働かないため、両方の用途に対して`clickUntilVisible`自体の既定の
 * 挙動として組み込んである(呼び分けの必要はない)。
 *
 * ### 例外: `trigger`と`expected`が排他的に入れ替わる操作(#1385)
 *
 * 上の「使ってはいけない場面」は主にトグル(再クリックで状態を反転させてしまう操作)を
 * 想定しているが、**`trigger`をクリックすると`expected`が現れると同時に`trigger`自身が
 * 条件描画で消える**設計(例: 「設定を削除」ボタンは対象が設定済みの間だけ描画され、
 * 削除に成功すると「未設定」の表示と入れ替わりに消える)であれば、削除のような一見
 * 副作用の強い操作にも安全に使える。1回目のクリックが実際には効いていて`expected`の
 * 出現待ちだけが`visibleTimeoutMs`超過で失敗しても、再試行時に`expected`が既に見えて
 * いればクリックはスキップされる(前述のstate-aware化)。仮にそのタイミングでも
 * `expected`がまだ描画されていなければ`trigger`はDOMから既に取り除かれているため、
 * 再クリックは物理的に起こり得ない(要素を待つactionabilityチェックで足止めされる
 * だけで、実際に2回目の操作が飛ぶことはない)。加えて、対象の操作自体がドメインとして
 * 冪等(既に削除済みの状態へもう一度「削除」を試みても実害が無い)であることも安全性の
 * 根拠にしている。
 *
 * `window.confirm()`のようなネイティブダイアログを伴う操作と組み合わせる場合は、
 * 下記{@link withDialogAccepted}も併用すること。
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
  let attempts = 0;
  await retryUntilPass(async () => {
    const isRetry = attempts > 0;
    attempts += 1;

    // 2回目以降に限り、既に目的の状態になっていればクリックをスキップする(#1360)。
    //
    // 【1回目には効かせないこと】この確認を1回目にも効かせると、`expected`が入室時点で
    // 既に見えている場合にクリックが**一度も行われない**まま成功扱いになる。例えば
    // 将来の退行でドロワーが初期状態から開いていたり無条件描画になったりすると、
    // 開くボタンが完全に壊れていてもシナリオが通ってしまう。それは受け入れテストが
    // 検出すべき欠陥そのものなので、1回目は必ずクリックする(#1360のレビュー指摘)。
    if (isRetry && (await expected.isVisible().catch(() => false))) {
      return;
    }

    await trigger.click();
    await expected.waitFor({ state: 'visible', timeout: options?.visibleTimeoutMs ?? DEFAULT_VISIBLE_TIMEOUT_MS });
  }, options);
}

/**
 * `action`の実行中、ネイティブダイアログ(`window.confirm()`など)を毎回自動でacceptする(#1385)。
 *
 * ## なぜ`page.once('dialog', ...)`では足りないか
 *
 * `page.once`は1回受けたら自動的に外れる。削除ボタンのクリックのように`window.confirm()`を
 * 出す操作を、ハイドレーション前の空振り対策として`clickUntilVisible`で再試行すると、
 * 2回目以降に出るダイアログは誰にも受けられなくなる。未処理のダイアログはPlaywrightが
 * **自動でdismiss**するため、再試行のクリックは確認ダイアログを閉じるだけで、対象の操作
 * (削除)は永久に実行されない。これが実際にrelease run 20260922T100609Z-3364342で
 * GA資格情報の削除を1件だけ失敗させた原因である。`page.on`は`action`の中で何度ダイアログが
 * 出ても毎回受ける。
 *
 * ## `page.off`を必ず呼ぶ理由
 *
 * `page.on`で登録したハンドラを外さずに残すと、同じ`page`を使う同一シナリオの後続ステップに
 * まで登録が漏れる。後続ステップが独自に`page.once('dialog', ...)`でダイアログを待って
 * いても、先に登録済みの本ヘルパーのハンドラがイベントを消費してしまい、後続側の
 * ハンドラには何も届かない。`action`が例外を投げた場合でも外れるよう、`finally`で行う。
 */
export async function withDialogAccepted<T>(page: Page, action: () => Promise<T>): Promise<T> {
  const acceptDialog = (dialog: Dialog): void => {
    void dialog.accept();
  };
  page.on('dialog', acceptDialog);
  try {
    return await action();
  } finally {
    page.off('dialog', acceptDialog);
  }
}
