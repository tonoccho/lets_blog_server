  const vscode = acquireVsCodeApi();
  /**
   * 同時にDOMへ載せる画像の上限(issue #1105)。
   *
   * batch size 16 × batch count 16 で最大256枚が並ぶ。1920×1080のPNGはbase64で1枚1MBを
   * 超えるため、全部を載せると数百MBがWebviewのメモリに滞留する。スクロールしても
   * この枚数を超えないよう、選択中の1枚を残して古い順に<img>のsrcを外す。
   */
  const MAX_LOADED_IMAGES = 24;

  /**
   * 直近の生成結果の「ファイル名だけ」(issue #1104)。base64本体はここに持たない。
   * 保存時にパネル側へ送り返さないための方針で、選択位置(index)だけを送る。
   * issue #1105 以降、パネルが送ってくるのもこのファイル名の一覧そのものになった。
   */
  let currentImages = [];
  let selectedIndex = 0;
  let chatHistory = [];
  /** 画像データを要求済みでまだ届いていない位置。二重要求を防ぐ。 */
  const pendingIndices = new Set();
  /** 画像データがDOMへ載っている位置。古い順に並べ、上限を超えたら先頭から追い出す。 */
  const loadedIndices = [];

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
    if (options.defaultAiProvider) {
      document.getElementById('aiProvider').value = options.defaultAiProvider;
    }
    fillSelect(document.getElementById('samplerName'), options.samplers, 'euler');
    fillSelect(document.getElementById('scheduler'), options.schedulers, 'normal');
    fillSelect(document.getElementById('checkpoint'), options.checkpoints, options.selectedCheckpoint);
    // プロジェクトのデフォルト生成サイズを初期値として反映する(issue #292)。
    if (options.defaultWidth) document.getElementById('width').value = String(options.defaultWidth);
    if (options.defaultHeight) document.getElementById('height').value = String(options.defaultHeight);

    // 生成時に実際に適用される既定値をUIへ反映する(issue #472)。
    // negative promptは未入力時のフォールバックとしてplaceholderに、
    // quality promptはpromptへ常に自動追加されるためヒントとして表示する。
    if (options.defaultNegativePrompt) {
      document.getElementById('negativePrompt').placeholder = options.defaultNegativePrompt;
    }
    const qualityPromptHint = document.getElementById('qualityPromptHint');
    if (options.defaultQualityPrompt) {
      qualityPromptHint.textContent = '生成時にpromptへ自動追加されます: ' + options.defaultQualityPrompt;
      qualityPromptHint.style.display = 'block';
    } else {
      qualityPromptHint.style.display = 'none';
    }

    const loraSelect = document.getElementById('loraName');
    loraSelect.innerHTML = '<option value="">なし</option>';
    for (const lora of options.loras) {
      const option = document.createElement('option');
      option.value = lora;
      option.textContent = lora;
      loraSelect.appendChild(option);
    }
  }

  /**
   * Image Galleryの「この設定で画像生成」から開かれた場合に、選択画像の生成設定を
   * フォームへ反映する(issue #294)。checkpoint/samplerName/scheduler/loraNameは
   * renderOptions()でoptionが揃った後に届く(パネル側でoptions送信後にprefillを送るため)。
   */
  function applyPrefill(detail) {
    if (!detail) return;
    document.getElementById('prompt').value = detail.prompt || '';
    document.getElementById('negativePrompt').value = detail.negativePrompt || '';
    if (detail.steps != null) document.getElementById('steps').value = String(detail.steps);
    if (detail.cfgScale != null) document.getElementById('cfgScale').value = String(detail.cfgScale);
    if (detail.samplerName) document.getElementById('samplerName').value = detail.samplerName;
    if (detail.scheduler) document.getElementById('scheduler').value = detail.scheduler;
    document.getElementById('seed').value = detail.seed != null ? String(detail.seed) : '';
    if (detail.width != null) document.getElementById('width').value = String(detail.width);
    if (detail.height != null) document.getElementById('height').value = String(detail.height);
    if (detail.batchSize != null) document.getElementById('batchSize').value = String(detail.batchSize);
    // 画像は`batch_count`を持たない(#1102の方針)ため、プリフィルでは引き継がず既定へ戻す。
    // 前回の回数が黙って残ると、意図しない枚数を生成してしまう(issue #1105)。
    document.getElementById('batchCount').value = '1';
    if (detail.checkpoint) document.getElementById('checkpoint').value = detail.checkpoint;

    const loraSelect = document.getElementById('loraName');
    loraSelect.value = detail.loraName || '';
    loraSelect.dispatchEvent(new Event('change'));
    if (detail.loraName && detail.loraWeight != null) {
      document.getElementById('loraWeight').value = String(detail.loraWeight);
    }

    showMessage('選択した画像の生成設定を反映しました。内容を確認して生成してください。', 'success');
    document.getElementById('prompt').focus();
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
      batchCount: Number(document.getElementById('batchCount').value),
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
    post('sendChat', {
      history: chatHistory.slice(0, -1),
      message: text,
      provider: document.getElementById('aiProvider').value || undefined,
    });
  }

  function generate() {
    if (LetsBlogLoading.isRunning()) return;
    const params = collectParams();
    if (!params) return;
    showMessage('', '');
    // 要求総枚数と、枚数によっては非常に長時間かかりうることを明示する(issue #1105)。
    const total = params.batchSize * params.batchCount;
    LetsBlogLoading.begin({
      buttonIds: ['generateButton', 'setAsEyecatchButton', 'addAsAssetButton'],
      text: '合計' + total + '枚を生成しています。枚数によっては非常に長い時間がかかります。'
        + '完了するまでこのパネルを閉じないでください。',
      kind: 'image',
      onCancel: function () { post('cancel'); },
    });
    post('generate', { params });
  }

  // mimeTypeはサーバー応答由来のため、既知の画像種別だけをデータURIへ組み立てる。
  function toDataUri(result) {
    const safeMimeType = /^image\/(png|jpeg|gif|webp|bmp|svg\+xml)$/.test(result.mimeType || '')
      ? result.mimeType
      : 'image/png';
    return 'data:' + safeMimeType + ';base64,' + result.dataBase64;
  }

  function updatePreviewInfo() {
    const current = currentImages[selectedIndex];
    const position = currentImages.length > 1
      ? ' (' + (selectedIndex + 1) + '/' + currentImages.length + '枚目)'
      : '';
    document.getElementById('previewInfo').textContent =
      'ファイル名: ' + current.fileName + position + '\n生成時刻: ' + new Date().toLocaleString();
  }

  /** index番目のサムネイルの<img>。サムネイル列を出していない(1枚だけの)ときはundefined。 */
  function thumbnailImage(index) {
    const thumbnail = document.getElementById('thumbnailStrip').children[index];
    return thumbnail && thumbnail.children[0];
  }

  /**
   * 画像データをパネルへ要求する(issue #1105)。既に載っている・要求済みのものは要求しない。
   * base64本体はパネル側が保持し、Webviewは必要になった1枚だけを取りに行く。
   */
  function requestImage(index) {
    if (pendingIndices.has(index) || loadedIndices.indexOf(index) >= 0) return;
    pendingIndices.add(index);
    post('requestImage', { index: index });
  }

  /**
   * DOMへ載っている画像を上限内に収める(issue #1105)。最後に載せたものを末尾へ回し、
   * 上限を超えたぶんを古い順に外す。選択中の1枚はプレビューの実体なので追い出さない。
   */
  function retainImage(index) {
    const at = loadedIndices.indexOf(index);
    if (at >= 0) loadedIndices.splice(at, 1);
    loadedIndices.push(index);
    while (loadedIndices.length > MAX_LOADED_IMAGES) {
      const oldest = loadedIndices[0] === selectedIndex ? 1 : 0;
      thumbnailImage(loadedIndices.splice(oldest, 1)[0]).src = '';
    }
  }

  /** 要求した1枚が届いたときの反映(issue #1105)。 */
  function applyImageData(payload) {
    pendingIndices.delete(payload.index);
    const dataUri = toDataUri(payload);
    const image = thumbnailImage(payload.index);
    if (image) {
      image.src = dataUri;
      retainImage(payload.index);
    }
    if (payload.index === selectedIndex) {
      document.getElementById('previewImage').src = dataUri;
    }
  }

  /**
   * 保存対象を選び直す(issue #1104)。拡大表示のsrcはサムネイルの<img>から取り出す。
   * base64本体をJS側の変数へ持たないための方針で、実体はDOMにしか置かない。
   * 追い出し済み(issue #1105)でsrcが無い場合は、その1枚だけを取り直す。
   */
  function selectImage(index) {
    selectedIndex = index;
    const thumbnails = document.getElementById('thumbnailStrip').children;
    for (let i = 0; i < thumbnails.length; i += 1) {
      const isSelected = i === index;
      thumbnails[i].className = isSelected ? 'thumbnail selected' : 'thumbnail';
      thumbnails[i].setAttribute('aria-pressed', isSelected ? 'true' : 'false');
    }
    const loaded = thumbnails[index] && thumbnails[index].children[0].src;
    document.getElementById('previewImage').src = loaded || '';
    if (!loaded) requestImage(index);
    updatePreviewInfo();
  }

  /**
   * 画面内に入ったサムネイルだけを読み込む(issue #1105)。256枚ぶんのbase64を一度に
   * 受け取らないための遅延読み込みで、Webview境界を越えるのは常に1枚ぶんに収まる。
   */
  const thumbnailObserver = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (entry.isIntersecting) requestImage(Number(entry.target.getAttribute('data-index')));
    });
  });

  /**
   * 生成された全枚数を表示する(issue #1104)。2枚以上のときだけサムネイル列を出し、
   * 1枚のときは従来どおり単一プレビューのままにする。
   *
   * 受け取るのはファイル名の一覧だけで、画像データは含まれない(issue #1105)。
   */
  function renderGenerated(results) {
    LetsBlogLoading.end();
    const images = Array.isArray(results) ? results : [results];
    const strip = document.getElementById('thumbnailStrip');
    thumbnailObserver.disconnect();
    strip.innerHTML = '';
    pendingIndices.clear();
    loadedIndices.length = 0;
    // 前回の生成結果が残ったままにならないようにする。
    document.getElementById('previewImage').src = '';
    if (images.length === 0) {
      currentImages = [];
      strip.style.display = 'none';
      showMessage('生成結果が空でした。もう一度生成してください。', 'error');
      return;
    }
    currentImages = images;
    selectedIndex = 0;
    document.getElementById('previewSection').style.display = 'block';

    if (images.length > 1) {
      images.forEach(function (result, index) {
        const thumbnail = document.createElement('button');
        thumbnail.type = 'button';
        thumbnail.className = 'thumbnail';
        thumbnail.setAttribute('data-index', String(index));
        thumbnail.setAttribute('aria-label', (index + 1) + '枚目: ' + result.fileName);
        thumbnail.addEventListener('click', function () { selectImage(index); });
        const image = document.createElement('img');
        image.alt = '';
        thumbnail.appendChild(image);
        strip.appendChild(thumbnail);
        thumbnailObserver.observe(thumbnail);
      });
      strip.style.display = 'flex';
      // 未選択の状態を作らないよう、先頭を既定で選択する(その1枚だけを取りに行く)。
      selectImage(0);
      showMessage(images.length + '枚生成しました。1枚選んで保存してください。', 'success');
    } else {
      strip.style.display = 'none';
      requestImage(0);
      updatePreviewInfo();
      showMessage('生成しました。', 'success');
    }
    // 生成後は次の操作(保存)へ進めるようフォーカスを移す。
    document.getElementById('setAsEyecatchButton').focus();
  }

  // 保存対象の画像データはパネル側が保持しているため、コマンドと選択位置だけを送る
  // (数MBのbase64文字列をWebview境界で往復させない)。
  function setAsEyecatch() {
    if (currentImages.length === 0 || LetsBlogLoading.isRunning()) return;
    LetsBlogLoading.begin({
      buttonIds: ['setAsEyecatchButton', 'addAsAssetButton'],
      text: 'アイキャッチとして保存しています…',
      kind: 'load',
    });
    post('setAsEyecatch', { index: selectedIndex });
  }

  function addAsAsset() {
    if (currentImages.length === 0 || LetsBlogLoading.isRunning()) return;
    LetsBlogLoading.begin({
      buttonIds: ['setAsEyecatchButton', 'addAsAssetButton'],
      text: 'アセットとして保存しています…',
      kind: 'load',
    });
    post('addAsAsset', { index: selectedIndex });
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
    // 通常のEnterは改行(textarea既定の挙動)。Ctrl/Cmd+Enterで送信する(issue #290)。
    // グローバルのCtrl+Enterハンドラ(画像生成)と重複発火しないようstopPropagationする。
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      e.stopPropagation();
      sendChat();
    }
  });

  window.addEventListener('message', (event) => {
    const { command, payload } = event.data;
    switch (command) {
      case 'options':
        LetsBlogLoading.end();
        renderOptions(payload);
        break;
      case 'prefill':
        applyPrefill(payload);
        break;
      case 'generated':
        renderGenerated(payload);
        break;
      case 'imageData':
        applyImageData(payload);
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
