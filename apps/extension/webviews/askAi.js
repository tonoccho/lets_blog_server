  const vscode = acquireVsCodeApi();
  let currentResult = null;

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = 'block';
  }

  function ask() {
    const question = document.getElementById('question').value.trim();
    if (!question || LetsBlogLoading.isRunning()) return;
    showMessage('', '');
    LetsBlogLoading.begin({
      buttonIds: ['askButton', 'applyButton'],
      text: 'Web検索を踏まえて回答を作成しています…',
      kind: 'chat',
      onCancel: function () { post('cancel'); },
    });
    post('ask', {
      question: question,
      provider: document.getElementById('aiProvider').value || undefined,
    });
  }

  function renderResult(result) {
    LetsBlogLoading.end();
    currentResult = result;
    document.getElementById('result').style.display = 'block';
    document.getElementById('answerArea').textContent = result.result;
    // 出典はサーバー/AI由来の文字列のため、HTMLとして組み立てずDOM APIで構築する
    // (タイトルやURLにマークアップが混入しても要素として解釈されないようにする)。
    const sourcesArea = document.getElementById('sourcesArea');
    sourcesArea.textContent = '';
    if (result.sources && result.sources.length > 0) {
      sourcesArea.appendChild(document.createTextNode('出典: '));
      result.sources.forEach((s, index) => {
        if (index > 0) sourcesArea.appendChild(document.createTextNode(', '));
        const link = document.createElement('a');
        link.href = s.url;
        link.textContent = s.title;
        sourcesArea.appendChild(link);
      });
    }
    document.getElementById('searchNoteArea').textContent = result.searchNote || '';
    document.getElementById('applyButton').focus();
    showMessage('回答を取得しました。', 'success');
  }

  function applyResult() {
    if (!currentResult || LetsBlogLoading.isRunning()) return;
    LetsBlogLoading.begin({ buttonIds: ['applyButton'], text: '記事へ挿入しています…', kind: 'load' });
    post('insert', { text: currentResult.result });
  }

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      ask();
    } else if (e.key === 'Escape' && LetsBlogLoading.isRunning()) {
      post('cancel');
    }
  });

  document.getElementById('askButton').addEventListener('click', ask);
  document.getElementById('applyButton').addEventListener('click', applyResult);

  window.addEventListener('message', (event) => {
    const { command, payload } = event.data;
    switch (command) {
      case 'init':
        if (payload.defaultAiProvider) {
          document.getElementById('aiProvider').value = payload.defaultAiProvider;
        }
        break;
      case 'answered':
        renderResult(payload);
        break;
      case 'inserted':
        LetsBlogLoading.end();
        showMessage('記事に挿入しました。', 'success');
        break;
      case 'cancelled':
        LetsBlogLoading.end();
        showMessage('処理をキャンセルしました。', '');
        break;
      case 'error':
        LetsBlogLoading.end();
        showMessage(payload.error, 'error');
        break;
    }
  });

  post('init');
