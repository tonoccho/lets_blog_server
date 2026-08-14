(function () {
  const vscode = acquireVsCodeApi();
  let existingCategories = [];
  /** スラッグを利用者が手で編集したら、タイトルからの自動入力を止める。 */
  let slugEditedByUser = false;
  /** AIブレインストーミングの会話履歴とセッションID(壁打ちチャットの継続に使う)。 */
  let chatHistory = [];
  let chatSessionId = undefined;

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = text ? 'block' : 'none';
  }

  /** タイトルからスラッグ候補を作る(拡張側 suggestSlugFromTitle と同じ規則)。 */
  function suggestSlug(title) {
    return title
      .toLowerCase()
      .replace(/[^a-z0-9\s-]/g, ' ')
      .trim()
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-')
      .replace(/^-|-$/g, '');
  }

  function renderProjects(projects, selectedProjectId) {
    const select = document.getElementById('projectSelect');
    select.textContent = '';
    if (!projects || projects.length === 0) {
      const option = document.createElement('option');
      option.textContent = 'プロジェクトがありません';
      option.disabled = true;
      option.selected = true;
      select.appendChild(option);
      showMessage('プロジェクトが登録されていません。先に管理画面でプロジェクトを作成してください。', 'error');
      document.getElementById('createButton').disabled = true;
      return;
    }
    projects.forEach(function (project) {
      const option = document.createElement('option');
      option.value = String(project.id);
      option.textContent = project.name;
      if (selectedProjectId != null && project.id === selectedProjectId) {
        option.selected = true;
      }
      select.appendChild(option);
    });
    loadCategoriesForSelectedProject();
  }

  function loadCategoriesForSelectedProject() {
    const projectId = Number(document.getElementById('projectSelect').value);
    if (!projectId) return;
    const container = document.getElementById('categoryCheckboxes');
    container.textContent = '';
    const hint = document.createElement('span');
    hint.className = 'hint';
    hint.textContent = 'カテゴリを読み込んでいます…';
    container.appendChild(hint);
    post('loadCategories', { projectId: projectId });
  }

  /** カテゴリ名(小文字)から{name, parentName}を探す。 */
  function findCategoryOption(name) {
    return existingCategories.find(function (o) { return o.name.toLowerCase() === name.toLowerCase(); });
  }

  /**
   * カテゴリ一覧を{name, parentName}のオブジェクト配列として描画する。
   * 子カテゴリのチェックボックスは、存在すれば親カテゴリも辿って自動的にチェックする(issue #289)。
   */
  function renderCategories(categories, selected) {
    existingCategories = categories || [];
    const container = document.getElementById('categoryCheckboxes');
    container.textContent = '';
    if (existingCategories.length === 0) {
      const hint = document.createElement('span');
      hint.className = 'hint';
      hint.textContent = '既存カテゴリを取得できませんでした(サイト未紐付け等)。新規カテゴリ欄に直接入力してください。';
      container.appendChild(hint);
      return;
    }
    const checkboxesByName = {};
    existingCategories.forEach(function (option, index) {
      const id = 'category-' + index;
      const label = document.createElement('label');
      if (option.parentName) {
        label.style.paddingLeft = '1.25em';
      }
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.id = id;
      checkbox.value = option.name;
      checkbox.checked = (selected || []).some(function (s) { return s.toLowerCase() === option.name.toLowerCase(); });
      label.setAttribute('for', id);
      label.appendChild(checkbox);
      label.appendChild(document.createTextNode(' ' + option.name));
      container.appendChild(label);
      checkboxesByName[option.name.toLowerCase()] = checkbox;
    });

    function selectAncestors(name) {
      let current = findCategoryOption(name);
      let guard = 0;
      while (current && current.parentName && guard < 20) {
        const parentCheckbox = checkboxesByName[current.parentName.toLowerCase()];
        if (parentCheckbox) parentCheckbox.checked = true;
        current = findCategoryOption(current.parentName);
        guard++;
      }
    }

    existingCategories.forEach(function (option) {
      const checkbox = checkboxesByName[option.name.toLowerCase()];
      if (!checkbox) return;
      if (checkbox.checked) selectAncestors(option.name);
      checkbox.addEventListener('change', function () {
        if (checkbox.checked) selectAncestors(option.name);
      });
    });
  }

  /** AIブレインストーミングのメッセージを画面へ追加し、履歴にも積む。 */
  function addBrainstormMessage(role, content) {
    chatHistory.push({ role: role, content: content });
    const messagesDiv = document.getElementById('brainstormMessages');
    const msgEl = document.createElement('div');
    msgEl.className = 'message ' + role;
    msgEl.textContent = content;
    messagesDiv.appendChild(msgEl);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
  }

  function currentProjectId() {
    return Number(document.getElementById('projectSelect').value) || undefined;
  }

  function sendBrainstormMessage() {
    const input = document.getElementById('brainstormInput');
    const text = input.value.trim();
    const projectId = currentProjectId();
    if (!text || LetsBlogLoading.isRunning()) return;
    if (!projectId) {
      showMessage('先にプロジェクトを選択してください。', 'error');
      return;
    }
    addBrainstormMessage('user', text);
    input.value = '';
    LetsBlogLoading.begin({
      buttonIds: ['brainstormSendButton', 'suggestMetadataButton', 'suggestStructureButton'],
      text: 'AIの応答を待っています…',
      kind: 'chat',
      onCancel: function () { post('cancel'); },
    });
    post('sendChat', { projectId: projectId, history: chatHistory.slice(0, -1), message: text, sessionId: chatSessionId });
  }

  function requestMetadataSuggestion() {
    if (LetsBlogLoading.isRunning()) return;
    const projectId = currentProjectId();
    if (!projectId) {
      showMessage('先にプロジェクトを選択してください。', 'error');
      return;
    }
    if (chatHistory.length === 0) {
      showMessage('先にAIブレインストーミングで壁打ちしてください。', 'error');
      return;
    }
    LetsBlogLoading.begin({
      buttonIds: ['suggestMetadataButton', 'brainstormSendButton'],
      text: 'タイトル・スラッグ・タグを提案しています…',
      kind: 'metadata',
      onCancel: function () { post('cancel'); },
    });
    post('suggestMetadata', { projectId: projectId, history: chatHistory });
  }

  function requestStructureSuggestion() {
    if (LetsBlogLoading.isRunning()) return;
    const projectId = currentProjectId();
    if (!projectId) {
      showMessage('先にプロジェクトを選択してください。', 'error');
      return;
    }
    if (chatHistory.length === 0) {
      showMessage('先にAIブレインストーミングで壁打ちしてください。', 'error');
      return;
    }
    LetsBlogLoading.begin({
      buttonIds: ['suggestStructureButton', 'brainstormSendButton'],
      text: '記事構成を提案しています…',
      kind: 'structure',
      onCancel: function () { post('cancel'); },
    });
    post('suggestStructure', { projectId: projectId, history: chatHistory });
  }

  /** 候補一覧をselectへ描画し、選択時に対応する入力欄へ反映する。 */
  function renderOptionList(selectId, targetInputId, options) {
    const select = document.getElementById(selectId);
    select.innerHTML = '';
    if (!options || options.length === 0) {
      const placeholder = document.createElement('option');
      placeholder.textContent = '候補がありません。下欄に直接入力してください。';
      placeholder.disabled = true;
      placeholder.selected = true;
      select.appendChild(placeholder);
      return;
    }
    options.forEach(function (value) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = value;
      select.appendChild(option);
    });
    select.onchange = function () {
      document.getElementById(targetInputId).value = select.value;
    };
  }

  function showMetadataSuggestion(suggestion) {
    const titles = suggestion.titles || [];
    const slugs = suggestion.slugs || [];
    renderOptionList('titleOptions', 'titleInput', titles);
    renderOptionList('slugOptions', 'slugInput', slugs);
    if (titles.length > 0) {
      document.getElementById('titleInput').value = titles[0];
      slugEditedByUser = true;
    }
    if (slugs.length > 0) {
      document.getElementById('slugInput').value = slugs[0];
    }
    const suggestedCategories = suggestion.categories || [];
    renderCategories(existingCategories, suggestedCategories);
    const unmatched = suggestedCategories.filter(function (c) {
      return !existingCategories.some(function (e) { return e.name.toLowerCase() === c.toLowerCase(); });
    });
    document.getElementById('newCategoriesInput').value = unmatched.join(', ');
    document.getElementById('tagsInput').value = (suggestion.tags || []).join(', ');
  }

  function collectMetadata() {
    const projectId = Number(document.getElementById('projectSelect').value);
    const title = document.getElementById('titleInput').value.trim();
    const slug = document.getElementById('slugInput').value.trim();

    if (!projectId) {
      showMessage('プロジェクトを選択してください。', 'error');
      return null;
    }
    if (!title) {
      showMessage('タイトルは必須です。', 'error');
      document.getElementById('titleInput').focus();
      return null;
    }
    if (!slug) {
      showMessage('スラッグは必須です。', 'error');
      document.getElementById('slugInput').focus();
      return null;
    }
    // ディレクトリ名になるため、パス区切りなどが混入しないことを確認する。
    if (!/^[a-z0-9][a-z0-9-]*$/.test(slug)) {
      showMessage('スラッグは半角英数字とハイフンのみで入力してください(先頭は英数字)。', 'error');
      document.getElementById('slugInput').focus();
      return null;
    }

    const checked = Array.from(
      document.querySelectorAll('#categoryCheckboxes input[type=checkbox]:checked')
    ).map(function (el) {
      return el.value;
    });
    const newCategories = document
      .getElementById('newCategoriesInput')
      .value.split(',')
      .map(function (s) { return s.trim(); })
      .filter(Boolean);
    const tags = document
      .getElementById('tagsInput')
      .value.split(',')
      .map(function (s) { return s.trim(); })
      .filter(Boolean);

    return {
      projectId: projectId,
      title: title,
      slug: slug,
      categories: Array.from(new Set(checked.concat(newCategories))),
      tags: tags,
      status: document.getElementById('statusSelect').value,
    };
  }

  function createArticle() {
    if (LetsBlogLoading.isRunning()) return;
    const metadata = collectMetadata();
    if (!metadata) return;
    const content = document.getElementById('structureTextarea').value;
    showMessage('', '');
    LetsBlogLoading.begin({ buttonIds: ['createButton'], text: '記事を作成しています…', kind: 'load' });
    post('createArticle', { metadata: metadata, content: content });
  }

  document.getElementById('titleInput').addEventListener('input', function (e) {
    if (slugEditedByUser) return;
    document.getElementById('slugInput').value = suggestSlug(e.target.value);
  });
  document.getElementById('slugInput').addEventListener('input', function () {
    slugEditedByUser = true;
  });
  document.getElementById('projectSelect').addEventListener('change', loadCategoriesForSelectedProject);
  document.getElementById('createButton').addEventListener('click', createArticle);
  document.getElementById('brainstormSendButton').addEventListener('click', sendBrainstormMessage);
  document.getElementById('brainstormInput').addEventListener('keydown', function (e) {
    // 通常のEnterは改行(textarea既定の挙動)。Ctrl/Cmd+Enterで送信する(issue #290)。
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      e.stopPropagation();
      sendBrainstormMessage();
    }
  });
  document.getElementById('suggestMetadataButton').addEventListener('click', requestMetadataSuggestion);
  document.getElementById('suggestStructureButton').addEventListener('click', requestStructureSuggestion);

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      createArticle();
    } else if (e.key === 'Escape' && LetsBlogLoading.isRunning()) {
      post('cancel');
    } else if (e.key === 'Escape' && !LetsBlogLoading.isRunning()) {
      // 作成をやめる場合の退避口。実行中は誤操作を防ぐため受け付けない。
      post('close');
    }
  });

  window.addEventListener('message', function (event) {
    const data = event.data;
    switch (data.command) {
      case 'projectList':
        LetsBlogLoading.end();
        renderProjects(data.payload.projects, data.payload.selectedProjectId);
        document.getElementById('titleInput').focus();
        break;
      case 'categoryList':
        renderCategories(data.payload.categories);
        break;
      case 'chatResponse':
        LetsBlogLoading.end();
        addBrainstormMessage('assistant', data.payload.reply);
        chatSessionId = data.payload.sessionId;
        break;
      case 'metadataSuggestion':
        LetsBlogLoading.end();
        showMetadataSuggestion(data.payload);
        showMessage('タイトル・スラッグ・タグを提案しました。候補から選ぶか、直接編集してください。', 'success');
        break;
      case 'structureSuggestion':
        LetsBlogLoading.end();
        document.getElementById('structureSection').style.display = 'block';
        document.getElementById('structureTextarea').value = data.payload.structure || '';
        break;
      case 'articleCreated':
        LetsBlogLoading.end();
        showMessage('articles/' + data.payload.slug + '/article.md を作成しました。', 'success');
        break;
      case 'cancelled':
        LetsBlogLoading.end();
        showMessage('操作をキャンセルしました。', '');
        break;
      case 'error':
        LetsBlogLoading.end();
        showMessage(data.payload.error, 'error');
        break;
    }
  });

  renderOptionList('titleOptions', 'titleInput', []);
  renderOptionList('slugOptions', 'slugInput', []);

  LetsBlogLoading.begin({ buttonIds: ['createButton'], text: 'プロジェクトを取得しています…', kind: 'load' });
  post('loadProjects');
})();
