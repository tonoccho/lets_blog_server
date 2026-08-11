  const vscode = acquireVsCodeApi();
  let currentImage = null;
  let chatHistory = [];

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = 'block';
  }

  function fillSelect(select, values, selected) {
    select.innerHTML = '';
    for (const value of values) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = value;
      if (value === selected) option.selected = true;
      select.appendChild(option);
    }
  }

  function renderOptions(options) {
    fillSelect(document.getElementById('samplerName'), options.samplers, 'euler');
    fillSelect(document.getElementById('scheduler'), options.schedulers, 'normal');
    fillSelect(document.getElementById('checkpoint'), options.checkpoints, options.selectedCheckpoint);

    const loraSelect = document.getElementById('loraName');
    loraSelect.innerHTML = '<option value="">なし</option>';
    for (const lora of options.loras) {
      const option = document.createElement('option');
      option.value = lora;
      option.textContent = lora;
      loraSelect.appendChild(option);
    }
  }

  document.getElementById('loraName').addEventListener('change', (e) => {
    const group = document.getElementById('loraWeightGroup');
    const enabled = Boolean(e.target.value);
    group.style.display = enabled ? 'block' : 'none';
    // 非表示の入力を支援技術が読み上げないようにする。
    group.setAttribute('aria-hidden', enabled ? 'false' : 'true');
    document.getElementById('loraWeight').disabled = !enabled;
  });

  function collectParams() {
    const prompt = document.getElementById('prompt').value.trim();
    if (!prompt) {
      showMessage('promptを入力してください。', 'error');
      return null;
    }
    const seedText = document.getElementById('seed').value.trim();
    const loraName = document.getElementById('loraName').value;
    return {
      prompt,
      negativePrompt: document.getElementById('negativePrompt').value || undefined,
      steps: Number(document.getElementById('steps').value),
      cfgScale: Number(document.getElementById('cfgScale').value),
      samplerName: document.getElementById('samplerName').value,
      scheduler: document.getElementById('scheduler').value,
      seed: seedText ? Number(seedText) : null,
      width: Number(document.getElementById('width').value),
      height: Number(document.getElementById('height').value),
      batchSize: Number(document.getElementById('batchSize').value),
      checkpoint: document.getElementById('checkpoint').value || undefined,
      loraName: loraName || undefined,
      loraWeight: loraName ? Number(document.getElementById('loraWeight').value) : undefined,
    };
  }

  function addChatMessage(role, content) {
    chatHistory.push({ role, content });
    const messagesDiv = document.getElementById('chatMessages');
    const msgEl = document.createElement('div');
    msgEl.className = 'message ' + role;
    msgEl.textContent = content;
    messagesDiv.appendChild(msgEl);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
  }

  function sendChat() {
    const input = document.getElementById('chatInput');
    const text = input.value.trim();
    if (!text || LetsBlogLoading.isRunning()) return;
    addChatMessage('user', text);
    input.value = '';
    showMessage('', '');
    LetsBlogLoading.begin({
      buttonIds: ['sendChatButton', 'generateButton'],
      text: 'プロンプトを生成しています…',
      kind: 'chat',
      onCancel: function () { post('cancel'); },
    });
    post('sendChat', { history: chatHistory.slice(0, -1), message: text });
  }

  function generate() {
    if (LetsBlogLoading.isRunning()) return;
    const params = collectParams();
    if (!params) return;
    showMessage('', '');
    LetsBlogLoading.begin({
      buttonIds: ['generateButton', 'setAsEyecatchButton', 'addAsAssetButton'],
      text: '画像を生成しています…',
      kind: 'image',
      onCancel: function () { post('cancel'); },
    });
    post('generate', { params });
  }

  function renderGenerated(result) {
    LetsBlogLoading.end();
    // base64本体は<img>のsrcへ渡した後は保持しない(Webview側にコピーを残さない)。
    currentImage = { fileName: result.fileName };
    document.getElementById('previewSection').style.display = 'block';
    // 生成後は次の操作(保存)へ進めるようフォーカスを移す。
    document.getElementById('setAsEyecatchButton').focus();
    // mimeTypeはサーバー応答由来のため、既知の画像種別だけをデータURIへ組み立てる。
    const safeMimeType = /^image\/(png|jpeg|gif|webp|bmp|svg\+xml)$/.test(result.mimeType || '')
      ? result.mimeType
      : 'image/png';
    document.getElementById('previewImage').src = 'data:' + safeMimeType + ';base64,' + result.dataBase64;
    document.getElementById('previewInfo').textContent =
      'ファイル名: ' + result.fileName + '\n生成時刻: ' + new Date().toLocaleString();
    showMessage('生成しました。', 'success');
  }

  // 保存対象の画像データはパネル側が保持しているため、コマンドだけを送る
  // (数MBのbase64文字列をWebview境界で往復させない)。
  function setAsEyecatch() {
    if (!currentImage || LetsBlogLoading.isRunning()) return;
    LetsBlogLoading.begin({
      buttonIds: ['setAsEyecatchButton', 'addAsAssetButton'],
      text: 'アイキャッチとして保存しています…',
      kind: 'load',
    });
    post('setAsEyecatch');
  }

  function addAsAsset() {
    if (!currentImage || LetsBlogLoading.isRunning()) return;
    LetsBlogLoading.begin({
      buttonIds: ['setAsEyecatchButton', 'addAsAssetButton'],
      text: 'アセットとして保存しています…',
      kind: 'load',
    });
    post('addAsAsset');
  }

  // Ctrl/Cmd+Enter で生成、Escape で実行中の処理を中断する。
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      generate();
    } else if (e.key === 'Escape' && LetsBlogLoading.isRunning()) {
      post('cancel');
    }
  });

  document.getElementById('generateButton').addEventListener('click', generate);
  document.getElementById('setAsEyecatchButton').addEventListener('click', setAsEyecatch);
  document.getElementById('addAsAssetButton').addEventListener('click', addAsAsset);
  document.getElementById('sendChatButton').addEventListener('click', sendChat);
  document.getElementById('chatInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') sendChat();
  });

  window.addEventListener('message', (event) => {
    const { command, payload } = event.data;
    switch (command) {
      case 'options':
        LetsBlogLoading.end();
        renderOptions(payload);
        break;
      case 'generated':
        renderGenerated(payload);
        break;
      case 'promptGenerated':
        LetsBlogLoading.end();
        addChatMessage('assistant', payload.prompt);
        document.getElementById('prompt').value = payload.prompt;
        break;
      case 'eyecatchSet':
      case 'assetAdded':
        LetsBlogLoading.end();
        showMessage('反映しました。', 'success');
        break;
      case 'cancelled':
        LetsBlogLoading.end();
        showMessage('生成をキャンセルしました。', '');
        break;
      case 'error':
        LetsBlogLoading.end();
        showMessage(payload.error, 'error');
        break;
    }
  });

  LetsBlogLoading.begin({ buttonIds: ['generateButton'], text: '生成オプションを取得しています…', kind: 'load' });
  post('loadOptions');
