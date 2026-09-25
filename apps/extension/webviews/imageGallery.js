(function () {
  const vscode = acquireVsCodeApi();

  let allImages = [];
  let page = 0;
  let perPage = 12;
  let selected = null;
  /** 読み込み済みサムネイルのデータURI(id -> dataUri)。ページ切替で使い回す。 */
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

  function totalPages() {
    return Math.max(1, Math.ceil(allImages.length / perPage));
  }

  function currentPageImages() {
    const start = page * perPage;
    return allImages.slice(start, start + perPage);
  }

  function renderGallery() {
    const gallery = document.getElementById('gallery');
    gallery.textContent = '';

    if (allImages.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'empty';
      empty.textContent = 'このプロジェクトに保存された生成画像はありません。「Let\'s Blog: Generate Image」で画像を生成すると、ここに表示されます。';
      gallery.appendChild(empty);
      updatePager();
      return;
    }

    currentPageImages().forEach(function (image) {
      const thumb = document.createElement('div');
      thumb.className = 'thumb';
      thumb.setAttribute('role', 'option');
      thumb.setAttribute('tabindex', '0');
      thumb.setAttribute('aria-selected', selected && selected.id === image.id ? 'true' : 'false');
      if (selected && selected.id === image.id) {
        thumb.classList.add('selected');
      }

      const cached = thumbnailCache.get(image.id);
      if (cached) {
        const img = document.createElement('img');
        img.src = cached;
        img.alt = (image.prompt || '生成画像') + ' のサムネイル';
        thumb.appendChild(img);
      } else {
        const placeholder = document.createElement('div');
        placeholder.className = 'placeholder';
        placeholder.textContent = '読み込み中…';
        thumb.appendChild(placeholder);
      }

      const caption = document.createElement('div');
      caption.className = 'caption';
      caption.textContent = truncate(image.prompt || '(プロンプトなし)', 40);
      thumb.appendChild(caption);

      thumb.addEventListener('click', function () { selectImage(image); });
      thumb.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          selectImage(image);
        } else if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
          e.preventDefault();
          moveFocus(thumb, e.key === 'ArrowRight' ? 1 : -1);
        }
      });
      thumb.addEventListener('contextmenu', function (e) {
        e.preventDefault();
        openContextMenu(image, e.clientX, e.clientY);
      });
      gallery.appendChild(thumb);
    });

    updatePager();
    requestMissingThumbnails();
  }

  function moveFocus(current, delta) {
    const items = Array.from(document.querySelectorAll('.thumb'));
    const next = items[items.indexOf(current) + delta];
    if (next) next.focus();
  }

  /** 右クリックした画像を対象に、次の「この設定で画像生成」用に保持する(issue #294)。 */
  let contextMenuTarget = null;

  function openContextMenu(image, x, y) {
    contextMenuTarget = image;
    const menu = document.getElementById('galleryContextMenu');
    menu.style.left = x + 'px';
    menu.style.top = y + 'px';
    menu.style.display = 'block';
  }

  function closeContextMenu() {
    contextMenuTarget = null;
    document.getElementById('galleryContextMenu').style.display = 'none';
  }

  /** 表示中ページのうち、まだ読み込んでいないサムネイルだけを要求する。 */
  function requestMissingThumbnails() {
    const missing = currentPageImages()
      .filter(function (image) { return !thumbnailCache.has(image.id); })
      .map(function (image) { return image.id; });
    if (missing.length === 0) return;

    LetsBlogLoading.begin({
      buttonIds: ['prevButton', 'nextButton'],
      text: 'サムネイルを読み込んでいます…',
      kind: 'load',
      onCancel: function () { post('cancel'); },
    });
    post('loadThumbnails', { imageIds: missing });
  }

  function updatePager() {
    const pager = document.getElementById('galleryPager');
    pager.style.display = allImages.length > perPage ? 'flex' : 'none';
    const start = allImages.length === 0 ? 0 : page * perPage + 1;
    const end = Math.min((page + 1) * perPage, allImages.length);
    document.getElementById('pageStatus').textContent = start + '-' + end + ' / ' + allImages.length + ' 件';
    document.getElementById('prevButton').disabled = page === 0;
    document.getElementById('nextButton').disabled = page >= totalPages() - 1;
  }

  function changePage(delta) {
    const next = page + delta;
    if (next < 0 || next >= totalPages()) return;
    page = next;
    renderGallery();
  }

  function selectImage(image) {
    selected = image;
    document.querySelectorAll('.thumb').forEach(function (el) {
      el.classList.remove('selected');
      el.setAttribute('aria-selected', 'false');
    });
    renderGallery();

    const section = document.getElementById('previewSection');
    section.style.display = 'block';
    const dataUri = thumbnailCache.get(image.id);
    const preview = document.getElementById('previewImage');
    if (dataUri) {
      preview.src = dataUri;
      preview.alt = (image.prompt || '生成画像') + ' のプレビュー';
    }
    document.getElementById('previewInfo').textContent =
      'ID: ' + image.id +
      (image.checkpoint ? '\nモデル: ' + image.checkpoint : '') +
      (image.createdAt ? '\n生成日時: ' + image.createdAt : '') +
      (image.prompt ? '\nprompt: ' + image.prompt : '');
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

  document.getElementById('insertButton').addEventListener('click', withSelected(function (image) {
    LetsBlogLoading.begin({
      buttonIds: ['insertButton', 'eyecatchButton', 'deleteButton'],
      text: '画像を保存して挿入しています…',
      kind: 'load',
    });
    post('insertImage', { imageId: image.id, prompt: image.prompt || '' });
  }));

  document.getElementById('eyecatchButton').addEventListener('click', withSelected(function (image) {
    LetsBlogLoading.begin({
      buttonIds: ['insertButton', 'eyecatchButton', 'deleteButton'],
      text: 'アイキャッチとして保存しています…',
      kind: 'load',
    });
    post('setAsEyecatch', { imageId: image.id });
  }));

  document.getElementById('deleteButton').addEventListener('click', withSelected(function (image) {
    LetsBlogLoading.begin({
      buttonIds: ['insertButton', 'eyecatchButton', 'deleteButton'],
      text: '削除の確認を待っています…',
      kind: 'load',
    });
    post('deleteImage', { imageId: image.id });
  }));

  document.getElementById('prevButton').addEventListener('click', function () { changePage(-1); });
  document.getElementById('nextButton').addEventListener('click', function () { changePage(1); });

  /** 他パネルでの生成等により一覧が古くなっている場合に、パネルを開き直さず再取得する(issue #295)。 */
  document.getElementById('refreshButton').addEventListener('click', function () {
    if (LetsBlogLoading.isRunning()) return;
    LetsBlogLoading.begin({
      buttonIds: ['refreshButton', 'prevButton', 'nextButton'],
      text: '画像一覧を再取得しています…',
      kind: 'load',
      onCancel: function () { post('cancel'); },
    });
    post('loadImages');
  });

  document.getElementById('regenerateMenuItem').addEventListener('click', function () {
    if (!contextMenuTarget) return;
    post('regenerateWithSettings', { imageId: contextMenuTarget.id });
    showMessage('Generate Imageパネルにこの設定を反映しています…', '');
    closeContextMenu();
  });

  // メニュー外のクリック/スクロール/Escapeでコンテキストメニューを閉じる。
  document.addEventListener('click', function (e) {
    const menu = document.getElementById('galleryContextMenu');
    if (menu.style.display !== 'none' && !menu.contains(e.target)) {
      closeContextMenu();
    }
  });
  document.addEventListener('scroll', closeContextMenu, true);

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && document.getElementById('galleryContextMenu').style.display !== 'none') {
      closeContextMenu();
    } else if (e.key === 'Escape' && LetsBlogLoading.isRunning()) {
      post('cancel');
    }
  });

  window.addEventListener('message', function (event) {
    const data = event.data;
    const payload = data.payload || {};
    switch (data.command) {
      case 'imageList':
        LetsBlogLoading.end();
        allImages = payload.images || [];
        perPage = payload.thumbnailsPerPage || perPage;
        page = 0;
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
      case 'imageInserted':
        LetsBlogLoading.end();
        showMessage('assets/' + payload.fileName + ' を記事へ挿入しました。', 'success');
        break;
      case 'eyecatchSet':
        LetsBlogLoading.end();
        showMessage('アイキャッチを assets/' + payload.fileName + ' に設定しました。', 'success');
        break;
      case 'imageDeleted':
        LetsBlogLoading.end();
        allImages = allImages.filter(function (i) { return i.id !== payload.imageId; });
        thumbnailCache.delete(payload.imageId);
        if (selected && selected.id === payload.imageId) {
          selected = null;
          document.getElementById('previewSection').style.display = 'none';
        }
        // 末尾ページの画像を消した場合にページ番号が範囲外にならないようにする。
        if (page >= totalPages()) page = totalPages() - 1;
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

  LetsBlogLoading.begin({ text: '生成画像の一覧を取得しています…', kind: 'load' });
  post('loadImages');
})();
