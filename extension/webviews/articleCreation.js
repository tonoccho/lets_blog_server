(function () {
  const vscode = acquireVsCodeApi();
  let existingCategories = [];
  /** スラッグを利用者が手で編集したら、タイトルからの自動入力を止める。 */
  let slugEditedByUser = false;

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

  function renderCategories(categories) {
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
    existingCategories.forEach(function (name, index) {
      const id = 'category-' + index;
      const label = document.createElement('label');
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.id = id;
      checkbox.value = name;
      label.setAttribute('for', id);
      label.appendChild(checkbox);
      label.appendChild(document.createTextNode(' ' + name));
      container.appendChild(label);
    });
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
    showMessage('', '');
    LetsBlogLoading.begin({ buttonIds: ['createButton'], text: '記事を作成しています…', kind: 'load' });
    post('createArticle', { metadata: metadata });
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

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      createArticle();
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
      case 'articleCreated':
        LetsBlogLoading.end();
        showMessage('articles/' + data.payload.slug + '/article.md を作成しました。', 'success');
        break;
      case 'error':
        LetsBlogLoading.end();
        showMessage(data.payload.error, 'error');
        break;
    }
  });

  LetsBlogLoading.begin({ buttonIds: ['createButton'], text: 'プロジェクトを取得しています…', kind: 'load' });
  post('loadProjects');
})();
