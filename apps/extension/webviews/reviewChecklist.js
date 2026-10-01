(function () {
  const vscode = acquireVsCodeApi();

  // 表示ラベルは日本語(issue #1216)。値はreviewChecklistLogic.tsのReviewChecklistStatusと一致させる。
  const STATUS_LABELS = {
    unresolved: '未対応',
    fixed: '修正済み',
    skipped: 'スキップ',
  };

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  // issue #1225: 件数・空状態・ジャンプ失敗の表示。view.recordedがfalse(レビュー未実行)のときは何も出さない。
  function renderView(view) {
    document.getElementById('jump-status').textContent = '';
    document.getElementById('summary').textContent = view.recorded ? '未対応: ' + view.unresolvedCount + '件' : '';
    document.getElementById('empty').textContent = view.isEmpty ? '指摘はありません' : '';
    renderSkippedSteps(view.skippedSteps);
    renderGroups(view.groups);
  }

  // issue #1545: 校閲でスキップされたステップ。理由はサーバ由来のためtextContent経由でのみ描画する。
  // 利用者が付ける対応状態の「スキップ」とは別物なので、文言もクラスも分ける。
  function renderSkippedSteps(skippedSteps) {
    const container = document.getElementById('skipped');
    container.innerHTML = '';
    if (!skippedSteps || skippedSteps.length === 0) {
      return;
    }
    const heading = document.createElement('h2');
    heading.className = 'skipped-heading';
    heading.textContent = '校閲が実行されなかったステップ';
    container.appendChild(heading);
    skippedSteps.forEach((skipped) => {
      const row = document.createElement('div');
      row.className = 'skipped-step';
      row.textContent = skipped.stepLabel + ': ' + skipped.reason;
      container.appendChild(row);
    });
  }

  function renderGroups(groups) {
    const container = document.getElementById('groups');
    container.innerHTML = '';
    (groups || []).forEach((group) => {
      container.appendChild(renderGroup(group));
    });
  }

  function renderGroup(group) {
    const section = document.createElement('section');
    section.className = 'step-group';

    const heading = document.createElement('h2');
    heading.className = 'step-title';
    heading.textContent = group.stepLabel;
    section.appendChild(heading);

    (group.items || []).forEach((item) => {
      section.appendChild(renderItem(item));
    });

    return section;
  }

  function renderItem(item) {
    const row = document.createElement('div');
    row.className = 'checklist-item status-' + item.status;

    // AI由来の文字列(originalText/message/suggestion)はtextContent経由でのみ描画する(innerHTMLは使わない)。
    // クリックで本文中の該当箇所へジャンプする(issue #1225)。位置は渡さず、拡張側が探し直す。
    const quote = document.createElement('button');
    quote.type = 'button';
    quote.className = 'original-text jump-target';
    quote.textContent = item.originalText;
    quote.addEventListener('click', () => {
      document.getElementById('jump-status').textContent = '';
      post('jump', { id: item.id });
    });
    row.appendChild(quote);

    const message = document.createElement('div');
    message.className = 'message';
    message.textContent = item.message;
    row.appendChild(message);

    if (item.suggestion) {
      const suggestion = document.createElement('div');
      suggestion.className = 'suggestion';
      suggestion.textContent = '提案: ' + item.suggestion;
      row.appendChild(suggestion);
    }

    row.appendChild(renderStatusSelect(item, row));

    return row;
  }

  function renderStatusSelect(item, row) {
    const select = document.createElement('select');
    select.className = 'status-select';
    select.setAttribute('aria-label', '対応状態');
    Object.keys(STATUS_LABELS).forEach((status) => {
      const option = document.createElement('option');
      option.value = status;
      option.textContent = STATUS_LABELS[status];
      if (status === item.status) {
        option.selected = true;
      }
      select.appendChild(option);
    });
    select.value = item.status;
    select.addEventListener('change', () => {
      row.className = 'checklist-item status-' + select.value;
      post('setStatus', { id: item.id, status: select.value });
    });
    return select;
  }

  window.addEventListener('message', (event) => {
    const { command, payload } = event.data;
    if (command === 'checklist') {
      renderView(payload);
    } else if (command === 'jumpNotFound') {
      document.getElementById('jump-status').textContent = payload.message;
    }
  });
}());
