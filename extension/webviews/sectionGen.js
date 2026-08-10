  const vscode = acquireVsCodeApi();
  let currentResult = null;
  let chatHistory = [];
  let pendingUserMessage = null;
  let subsectionHeadings = [];

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = 'block';
  }

  function applyModeVisibility() {
    const mode = document.getElementById('mode').value;
    document.getElementById('precedingContextGroup').style.display = mode === 'body' ? 'block' : 'none';
    document.getElementById('headingGroup').style.display = mode === 'lead' ? 'none' : 'block';
    const hint = document.getElementById('outlineHint');
    if ((mode === 'lead' || mode === 'lead-subsections') && subsectionHeadings.length > 0) {
      const label = mode === 'lead' ? '記事の構成: ' : 'サブセクション: ';
      hint.textContent = label + subsectionHeadings.join(' / ');
    } else {
      hint.textContent = '';
    }
  }

  document.getElementById('mode').addEventListener('change', applyModeVisibility);

  function baseParams() {
    return {
      mode: document.getElementById('mode').value,
      heading: document.getElementById('heading').value.trim() || undefined,
      articleTitle: document.getElementById('articleTitle').value || undefined,
      precedingContext: document.getElementById('precedingContext').value || undefined,
      subsectionHeadings: subsectionHeadings.length > 0 ? subsectionHeadings : undefined,
    };
  }

  function generate() {
    if (LetsBlogLoading.isRunning()) return;
    showMessage('', '');
    LetsBlogLoading.begin({
      buttonIds: ['generateButton', 'refineButton', 'insertButton'],
      text: '本文を生成しています…',
      kind: 'generate',
      onCancel: function () { post('cancel'); },
    });
    chatHistory = [];
    pendingUserMessage = null;
    document.getElementById('messages').innerHTML = '';
    post('generate', { params: baseParams() });
  }

  function refine() {
    const instruction = document.getElementById('refineInput').value.trim();
    if (!instruction || !currentResult || LetsBlogLoading.isRunning()) return;
    showMessage('', '');
    LetsBlogLoading.begin({
      buttonIds: ['generateButton', 'refineButton', 'insertButton'],
      text: '指示を反映して再生成しています…',
      kind: 'generate',
      onCancel: function () { post('cancel'); },
    });
    pendingUserMessage = instruction;
    post('generate', { params: Object.assign(baseParams(), { history: chatHistory, message: instruction }) });
  }

  function appendBubble(role, text) {
    const div = document.createElement('div');
    div.className = 'bubble ' + role;
    const roleLabel = document.createElement('span');
    roleLabel.className = 'role';
    roleLabel.textContent = role === 'assistant' ? 'AI提案' : '追加の指示';
    div.appendChild(roleLabel);
    const body = document.createElement('div');
    body.textContent = text;
    div.appendChild(body);
    document.getElementById('messages').appendChild(div);
  }

  function renderResult(result) {
    LetsBlogLoading.end();
    currentResult = result;
    document.getElementById('refineInput').value = '';
    document.getElementById('chat').style.display = 'block';

    if (pendingUserMessage) {
      chatHistory.push({ role: 'user', content: pendingUserMessage });
      appendBubble('user', pendingUserMessage);
      pendingUserMessage = null;
    }
    chatHistory.push({ role: 'assistant', content: result.result });
    appendBubble('assistant', result.result);

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

    const noteArea = document.getElementById('searchNoteArea');
    noteArea.textContent = result.searchNote || '';

    showMessage('生成しました。', 'success');
  }

  function insertText() {
    if (!currentResult || LetsBlogLoading.isRunning()) return;
    LetsBlogLoading.begin({ buttonIds: ['insertButton'], text: '記事へ挿入しています…', kind: 'load' });
    post('insert', { text: currentResult.result });
  }

  document.getElementById('generateButton').addEventListener('click', generate);
  document.getElementById('refineButton').addEventListener('click', refine);
  document.getElementById('insertButton').addEventListener('click', insertText);

  window.addEventListener('message', (event) => {
    const { command, payload } = event.data;
    switch (command) {
      case 'init': {
        document.getElementById('articleTitle').value = payload.articleTitle || '';
        const ctx = payload.sectionContext || {};
        document.getElementById('mode').value = ctx.mode || 'body';
        if (ctx.heading) {
          document.getElementById('heading').value = ctx.heading;
        }
        subsectionHeadings = ctx.subsectionHeadings || [];
        if (ctx.mode === 'body' && ctx.precedingContext) {
          document.getElementById('precedingContext').value = ctx.precedingContext;
        } else if (payload.selectedText) {
          document.getElementById('precedingContext').value = payload.selectedText;
        }
        applyModeVisibility();
        break;
      }
      case 'generated':
        renderResult(payload);
        break;
      case 'inserted':
        LetsBlogLoading.end();
        showMessage('記事に挿入しました。', 'success');
        break;
      case 'cancelled':
        LetsBlogLoading.end();
        pendingUserMessage = null;
        showMessage('生成をキャンセルしました。', '');
        break;
      case 'error':
        LetsBlogLoading.end();
        pendingUserMessage = null;
        showMessage(payload.error, 'error');
        break;
    }
  });

  post('init');
