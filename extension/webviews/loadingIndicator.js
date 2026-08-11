/**
 * 各Webviewパネル共通のローディング表示。
 *
 * ボタンを押した後に何が起きているか分からない、待っている間に同じボタンを
 * 連打してしまう、という問題を解消するために次を担う:
 *  - 実行中のボタンを無効化し、押せないことを見た目でも示す
 *  - 進行中であることをスピナーで示し、処理ステップを文言で伝える
 *  - 経過時間と(推定できる場合は)目安時間を表示する
 *  - 長時間処理をキャンセルできるようにする
 *
 * すべてのパネルが `LetsBlogLoading` として参照する。
 */
const LetsBlogLoading = (function () {
  /** 処理ごとの目安時間(ミリ秒)。実測に基づく概算で、超過しても異常ではない。 */
  const ESTIMATED_DURATION_MS = {
    generate: 40000,
    image: 60000,
    chat: 15000,
    structure: 20000,
    metadata: 20000,
    load: 5000,
    publish: 30000,
  };

  let state = null;

  function ensureContainer() {
    let el = document.getElementById('letsBlogLoading');
    if (!el) {
      el = document.createElement('div');
      el.id = 'letsBlogLoading';
      el.className = 'lb-loading';
      // スクリーンリーダーへ進捗の変化を伝える。
      el.setAttribute('role', 'status');
      el.setAttribute('aria-live', 'polite');
      el.innerHTML =
        '<span class="lb-spinner" aria-hidden="true"></span>' +
        '<span class="lb-loading-text"></span>' +
        '<span class="lb-loading-elapsed"></span>' +
        '<button type="button" class="lb-cancel-button">キャンセル</button>';
      document.body.appendChild(el);
    }
    return el;
  }

  function formatSeconds(ms) {
    return Math.floor(ms / 1000) + '秒';
  }

  function renderElapsed() {
    if (!state) return;
    const elapsed = Date.now() - state.startedAt;
    const estimate = state.estimateMs;
    const parts = ['経過 ' + formatSeconds(elapsed)];
    if (estimate) {
      parts.push(
        elapsed < estimate ? '目安 ' + formatSeconds(estimate) : '目安時間を超過しています'
      );
    }
    state.container.querySelector('.lb-loading-elapsed').textContent = '(' + parts.join(' / ') + ')';
  }

  return {
    /**
     * 処理の開始。対象ボタンを無効化し、ローディング表示を出す。
     * @param {object} options
     * @param {string[]} [options.buttonIds] 実行中に無効化するボタンのid
     * @param {string} options.text 最初に表示するステップ文言
     * @param {keyof ESTIMATED_DURATION_MS} [options.kind] 目安時間の種別
     * @param {() => void} [options.onCancel] キャンセル時に呼ばれる。省略時はキャンセル不可
     */
    begin: function (options) {
      const container = ensureContainer();
      const buttonIds = options.buttonIds || [];
      const disabled = [];
      buttonIds.forEach(function (id) {
        const button = document.getElementById(id);
        if (button && !button.disabled) {
          button.disabled = true;
          // 支援技術にも「処理中」であることを伝える。
          button.setAttribute('aria-busy', 'true');
          disabled.push(button);
        }
      });

      state = {
        container: container,
        disabled: disabled,
        startedAt: Date.now(),
        estimateMs: options.kind ? ESTIMATED_DURATION_MS[options.kind] : undefined,
        onCancel: options.onCancel,
      };

      container.querySelector('.lb-loading-text').textContent = options.text || '処理しています…';
      const cancelButton = container.querySelector('.lb-cancel-button');
      cancelButton.style.display = options.onCancel ? 'inline-block' : 'none';
      cancelButton.disabled = false;
      cancelButton.onclick = function () {
        cancelButton.disabled = true;
        container.querySelector('.lb-loading-text').textContent = 'キャンセルしています…';
        if (state && state.onCancel) state.onCancel();
      };

      container.classList.add('visible');
      renderElapsed();
      state.timer = setInterval(renderElapsed, 1000);
    },

    /** 処理ステップの文言を更新する(例: "Issueを取得しています…" → "構成を生成しています…")。 */
    step: function (text) {
      if (!state) return;
      state.container.querySelector('.lb-loading-text').textContent = text;
    },

    /** 処理の終了。ボタンを復帰させ、ローディング表示を隠す。 */
    end: function () {
      if (!state) return;
      clearInterval(state.timer);
      state.disabled.forEach(function (button) {
        button.disabled = false;
        button.removeAttribute('aria-busy');
      });
      state.container.classList.remove('visible');
      state = null;
    },

    /** 実行中かどうか。二重実行の抑止に使う。 */
    isRunning: function () {
      return state !== null;
    },
  };
})();
