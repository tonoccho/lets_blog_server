(function () {
  const vscode = acquireVsCodeApi();

  let allDiagrams = [];
  let selected = null;
  /** 読み込み済みサムネイルのデータURI(id -> dataUri)。 */
  const thumbnailCache = new Map();

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = text ? 'block' : 'none';
  }

  function renderGallery() {
    const gallery = document.getElementById('gallery');
    gallery.textContent = '';

    if (allDiagrams.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'empty';
      empty.textContent = 'このプロジェクトに保存されたダイアグラムはありません。「Let\'s Blog: Add New Diagram」で作成すると、ここに表示されます。';
      gallery.appendChild(empty);
      return;
    }

    allDiagrams.forEach(function (diagram) {
      const thumb = document.createElement('div');
      thumb.className = 'thumb';
      thumb.setAttribute('role', 'option');
      thumb.setAttribute('tabindex', '0');
      thumb.setAttribute('aria-selected', selected && selected.id === diagram.id ? 'true' : 'false');
      if (selected && selected.id === diagram.id) {
        thumb.classList.add('selected');
      }

      const cached = thumbnailCache.get(diagram.id);
      if (cached) {
        const img = document.createElement('img');
        img.src = cached;
        img.alt = diagram.name + ' のサムネイル';
        thumb.appendChild(img);
      } else {
        const placeholder = document.createElement('div');
        placeholder.className = 'placeholder';
        placeholder.textContent = '読み込み中…';
        thumb.appendChild(placeholder);
      }

      const caption = document.createElement('div');
      caption.className = 'caption';
      caption.textContent = truncate(diagram.name, 40);
      thumb.appendChild(caption);

      thumb.addEventListener('click', function () { selectDiagram(diagram); });
      thumb.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          selectDiagram(diagram);
        } else if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
          e.preventDefault();
          moveFocus(thumb, e.key === 'ArrowRight' ? 1 : -1);
        }
      });
      gallery.appendChild(thumb);
    });

    requestMissingThumbnails();
  }

  function moveFocus(current, delta) {
    const items = Array.from(document.querySelectorAll('.thumb'));
    const next = items[items.indexOf(current) + delta];
    if (next) next.focus();
  }

  function requestMissingThumbnails() {
    const missing = allDiagrams
      .filter(function (diagram) { return !thumbnailCache.has(diagram.id); })
      .map(function (diagram) { return diagram.id; });
    if (missing.length === 0) return;

    LetsBlogLoading.begin({
      text: 'サムネイルを読み込んでいます…',
      kind: 'load',
      onCancel: function () { post('cancel'); },
    });
    post('loadThumbnails', { diagramIds: missing });
  }

  function selectDiagram(diagram) {
    selected = diagram;
    document.querySelectorAll('.thumb').forEach(function (el) {
      el.classList.remove('selected');
      el.setAttribute('aria-selected', 'false');
    });
    renderGallery();

    const section = document.getElementById('previewSection');
    section.style.display = 'block';
    const dataUri = thumbnailCache.get(diagram.id);
    const preview = document.getElementById('previewImage');
    if (dataUri) {
      preview.src = dataUri;
      preview.alt = diagram.name + ' のプレビュー';
    }
    document.getElementById('previewInfo').textContent =
      'ID: ' + diagram.id +
      '\n名前: ' + diagram.name +
      (diagram.updatedAt ? '\n更新日時: ' + diagram.updatedAt : '');
    document.getElementById('insertButton').focus();
  }

  function truncate(text, max) {
    return text.length <= max ? text : text.slice(0, max) + '…';
  }

  function withSelected(action) {
    return function () {
      if (!selected || LetsBlogLoading.isRunning()) return;
      action(selected);
    };
  }

  document.getElementById('insertButton').addEventListener('click', withSelected(function (diagram) {
    LetsBlogLoading.begin({
      buttonIds: ['insertButton', 'deleteButton'],
      text: 'ダイアグラムを保存して挿入しています…',
      kind: 'load',
    });
    post('insertDiagram', { diagramId: diagram.id, name: diagram.name });
  }));

  document.getElementById('deleteButton').addEventListener('click', withSelected(function (diagram) {
    LetsBlogLoading.begin({
      buttonIds: ['insertButton', 'deleteButton'],
      text: '削除の確認を待っています…',
      kind: 'load',
    });
    post('deleteDiagram', { diagramId: diagram.id });
  }));

  document.getElementById('refreshButton').addEventListener('click', function () {
    if (LetsBlogLoading.isRunning()) return;
    LetsBlogLoading.begin({
      buttonIds: ['refreshButton'],
      text: 'ダイアグラム一覧を再取得しています…',
      kind: 'load',
      onCancel: function () { post('cancel'); },
    });
    post('loadDiagrams');
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && LetsBlogLoading.isRunning()) {
      post('cancel');
    }
  });

  window.addEventListener('message', function (event) {
    const data = event.data;
    const payload = data.payload || {};
    switch (data.command) {
      case 'diagramList':
        LetsBlogLoading.end();
        allDiagrams = payload.diagrams || [];
        renderGallery();
        break;
      case 'thumbnails':
        LetsBlogLoading.end();
        (payload.thumbnails || []).forEach(function (t) { thumbnailCache.set(t.id, t.dataUri); });
        renderGallery();
        if (selected && thumbnailCache.has(selected.id)) {
          document.getElementById('previewImage').src = thumbnailCache.get(selected.id);
        }
        break;
      case 'diagramInserted':
        LetsBlogLoading.end();
        showMessage('assets/' + payload.fileName + ' を記事へ挿入しました。', 'success');
        break;
      case 'diagramDeleted':
        LetsBlogLoading.end();
        allDiagrams = allDiagrams.filter(function (d) { return d.id !== payload.diagramId; });
        thumbnailCache.delete(payload.diagramId);
        if (selected && selected.id === payload.diagramId) {
          selected = null;
          document.getElementById('previewSection').style.display = 'none';
        }
        renderGallery();
        showMessage('サーバーから削除しました。', 'success');
        break;
      case 'deleteCancelled':
        LetsBlogLoading.end();
        showMessage('削除をキャンセルしました。', '');
        break;
      case 'cancelled':
        LetsBlogLoading.end();
        showMessage('読み込みをキャンセルしました。', '');
        break;
      case 'error':
        LetsBlogLoading.end();
        showMessage(payload.error, 'error');
        break;
    }
  });

  LetsBlogLoading.begin({ text: 'ダイアグラムの一覧を取得しています…', kind: 'load' });
  post('loadDiagrams');
})();
