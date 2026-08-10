  const vscode = acquireVsCodeApi();
  let currentImage = null;

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
    document.getElementById('loraWeightGroup').style.display = e.target.value ? 'block' : 'none';
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

  function generate() {
    const params = collectParams();
    if (!params) return;
    document.getElementById('generateButton').disabled = true;
    showMessage('生成しています…', '');
    post('generate', { params });
  }

  function renderGenerated(result) {
    // base64本体は<img>のsrcへ渡した後は保持しない(Webview側にコピーを残さない)。
    currentImage = { fileName: result.fileName };
    document.getElementById('generateButton').disabled = false;
    document.getElementById('previewSection').style.display = 'block';
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
    if (!currentImage) return;
    post('setAsEyecatch');
  }

  function addAsAsset() {
    if (!currentImage) return;
    post('addAsAsset');
  }

  document.getElementById('generateButton').addEventListener('click', generate);
  document.getElementById('setAsEyecatchButton').addEventListener('click', setAsEyecatch);
  document.getElementById('addAsAssetButton').addEventListener('click', addAsAsset);

  window.addEventListener('message', (event) => {
    const { command, payload } = event.data;
    switch (command) {
      case 'options':
        renderOptions(payload);
        break;
      case 'generated':
        renderGenerated(payload);
        break;
      case 'eyecatchSet':
      case 'assetAdded':
        showMessage('反映しました。', 'success');
        break;
      case 'error':
        document.getElementById('generateButton').disabled = false;
        showMessage(payload.error, 'error');
        break;
    }
  });

  post('loadOptions');
