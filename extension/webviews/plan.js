  const vscode = acquireVsCodeApi();
  let selectedIssue = null;
  let sessionId = undefined;
  let chatHistory = [];
  let existingCategories = [];

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  // Issue一覧はページ単位で描画する。件数が多いリポジトリで全件を一度にDOM化すると
  // パネルの初期表示が重くなるため、1ページ分だけを生成する。
  const ISSUES_PER_PAGE = 20;
  let allIssues = [];
  let issuePage = 0;

  function totalIssuePages() {
    return Math.max(1, Math.ceil(allIssues.length / ISSUES_PER_PAGE));
  }

  function renderIssueList(issues) {
    allIssues = issues || [];
    issuePage = 0;
    renderIssuePage();
  }

  function renderIssuePage() {
    const el = document.getElementById('issueList');
    el.textContent = '';
    if (allIssues.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = '未割り当てのissueはありません。';
      el.appendChild(empty);
      updateIssuePager();
      return;
    }

    const start = issuePage * ISSUES_PER_PAGE;
    allIssues.slice(start, start + ISSUES_PER_PAGE).forEach((issue) => {
      const item = document.createElement('div');
      item.className = 'issue-item';
      item.textContent = '#' + issue.number + ': ' + issue.title;
      // リストボックスの選択肢として扱い、Tab/矢印キーで到達・選択できるようにする。
      item.setAttribute('role', 'option');
      item.setAttribute('tabindex', '0');
      const isSelected = selectedIssue && selectedIssue.number === issue.number;
      item.setAttribute('aria-selected', isSelected ? 'true' : 'false');
      if (isSelected) {
        item.classList.add('selected');
      }
      item.addEventListener('click', () => selectIssue(issue, item));
      item.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          selectIssue(issue, item);
        } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
          e.preventDefault();
          moveIssueFocus(item, e.key === 'ArrowDown' ? 1 : -1);
        }
      });
      el.appendChild(item);
    });
    updateIssuePager();
  }

  function updateIssuePager() {
    const pager = document.getElementById('issuePager');
    const status = document.getElementById('issuePageStatus');
    const prev = document.getElementById('issuePrevButton');
    const next = document.getElementById('issueNextButton');
    if (!pager) return;

    pager.style.display = allIssues.length > ISSUES_PER_PAGE ? 'flex' : 'none';
    const start = allIssues.length === 0 ? 0 : issuePage * ISSUES_PER_PAGE + 1;
    const end = Math.min((issuePage + 1) * ISSUES_PER_PAGE, allIssues.length);
    status.textContent = start + '-' + end + ' / ' + allIssues.length + ' 件';
    prev.disabled = issuePage === 0;
    next.disabled = issuePage >= totalIssuePages() - 1;
  }

  /** 矢印キーでのフォーカス移動。ページ端では隣のページへ送る。 */
  function moveIssueFocus(current, delta) {
    const items = Array.from(document.querySelectorAll('.issue-item'));
    const index = items.indexOf(current);
    const next = items[index + delta];
    if (next) {
      next.focus();
      return;
    }
    if (delta > 0 && issuePage < totalIssuePages() - 1) {
      changeIssuePage(1);
      const first = document.querySelector('.issue-item');
      if (first) first.focus();
    } else if (delta < 0 && issuePage > 0) {
      changeIssuePage(-1);
      const all = document.querySelectorAll('.issue-item');
      if (all.length > 0) all[all.length - 1].focus();
    }
  }

  function changeIssuePage(delta) {
    const next = issuePage + delta;
    if (next < 0 || next >= totalIssuePages()) return;
    issuePage = next;
    renderIssuePage();
  }

  function selectIssue(issue, el) {
    selectedIssue = issue;
    sessionId = undefined;
    chatHistory = [];
    document.getElementById('messages').innerHTML = '';
    document.querySelectorAll('.issue-item').forEach((n) => {
      n.classList.remove('selected');
      n.setAttribute('aria-selected', 'false');
    });
    el.classList.add('selected');
    el.setAttribute('aria-selected', 'true');
    document.getElementById('chatSection').style.display = 'block';
    document.getElementById('structureSection').style.display = 'block';
    document.getElementById('structureTextarea').value = '';
    document.getElementById('structureAcceptedBadge').style.display = 'none';
    document.getElementById('metadataSection').style.display = 'none';
    // 選択直後に入力できるよう、次の操作先へフォーカスを移す。
    document.getElementById('chatInput').focus();
  }

  function addMessage(role, content) {
    chatHistory.push({ role, content });
    const messagesDiv = document.getElementById('messages');
    const msgEl = document.createElement('div');
    msgEl.className = 'message ' + role;
    msgEl.textContent = content;
    messagesDiv.appendChild(msgEl);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
  }

  function sendMessage() {
    const input = document.getElementById('chatInput');
    const text = input.value.trim();
    if (!text || !selectedIssue || LetsBlogLoading.isRunning()) return;
    addMessage('user', text);
    input.value = '';
    LetsBlogLoading.begin({
      buttonIds: ['sendButton', 'suggestButton', 'suggestStructureButton'],
      text: 'AIの応答を待っています…',
      kind: 'chat',
      onCancel: function () { post('cancel'); },
    });
    post('sendChat', { history: chatHistory.slice(0, -1), message: text, sessionId, issueNumber: selectedIssue.number });
  }

  function requestStructureSuggestion() {
    if (LetsBlogLoading.isRunning()) return;
    if (!selectedIssue || chatHistory.length === 0) {
      showMessage('先にチャットで壁打ちしてください。', 'error');
      return;
    }
    LetsBlogLoading.begin({
      buttonIds: ['suggestStructureButton', 'acceptStructureButton', 'sendButton'],
      text: '記事構成を生成しています…',
      kind: 'structure',
      onCancel: function () { post('cancel'); },
    });
    post('suggestStructure', { history: chatHistory });
  }

  function acceptStructure() {
    if (LetsBlogLoading.isRunning()) return;
    const structure = document.getElementById('structureTextarea').value.trim();
    if (!selectedIssue || !structure) {
      showMessage('構成案が空です。', 'error');
      return;
    }
    LetsBlogLoading.begin({
      buttonIds: ['acceptStructureButton'],
      text: 'GitHub Issueの本文を更新しています…',
      kind: 'load',
    });
    post('acceptStructure', { issueNumber: selectedIssue.number, structure });
  }

  function requestMetadataSuggestion() {
    if (LetsBlogLoading.isRunning()) return;
    LetsBlogLoading.begin({
      buttonIds: ['suggestButton', 'sendButton'],
      text: 'メタデータを提案しています…',
      kind: 'metadata',
      onCancel: function () { post('cancel'); },
    });
    post('suggestMetadata', { history: chatHistory });
  }

  function renderCategoryCheckboxes(selected) {
    const container = document.getElementById('categoryCheckboxes');
    container.innerHTML = '';
    if (existingCategories.length === 0) {
      container.innerHTML = '<span class="hint">既存カテゴリを取得できませんでした(サイト未紐付け等)。新規カテゴリ欄に直接入力してください。</span>';
      return;
    }
    existingCategories.forEach((name, index) => {
      const label = document.createElement('label');
      const checkbox = document.createElement('input');
      const id = 'category-' + index;
      checkbox.type = 'checkbox';
      checkbox.id = id;
      checkbox.value = name;
      checkbox.checked = (selected || []).some((s) => s.toLowerCase() === name.toLowerCase());
      label.setAttribute('for', id);
      label.appendChild(checkbox);
      label.appendChild(document.createTextNode(' ' + name));
      container.appendChild(label);
    });
  }

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
    options.forEach((value) => {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = value;
      select.appendChild(option);
    });
    select.onchange = () => {
      document.getElementById(targetInputId).value = select.value;
    };
  }

  function showMetadataForm(suggestion) {
    const titles = suggestion.titles || [];
    const slugs = suggestion.slugs || [];
    renderOptionList('titleOptions', 'titleInput', titles);
    renderOptionList('slugOptions', 'slugInput', slugs);
    document.getElementById('titleInput').value = titles[0] || '';
    document.getElementById('slugInput').value = slugs[0] || '';
    const suggestedCategories = suggestion.categories || [];
    renderCategoryCheckboxes(suggestedCategories);
    const unmatched = suggestedCategories.filter(
      (c) => !existingCategories.some((e) => e.toLowerCase() === c.toLowerCase())
    );
    document.getElementById('newCategoriesInput').value = unmatched.join(', ');
    document.getElementById('tagsInput').value = (suggestion.tags || []).join(', ');
    document.getElementById('metadataSection').style.display = 'block';
    document.getElementById('titleInput').focus();
  }

  function approveMetadata() {
    const title = document.getElementById('titleInput').value.trim();
    const slug = document.getElementById('slugInput').value.trim();
    const checkedCategories = Array.from(
      document.querySelectorAll('#categoryCheckboxes input[type=checkbox]:checked')
    ).map((el) => el.value);
    const newCategories = document.getElementById('newCategoriesInput').value.split(',').map((s) => s.trim()).filter(Boolean);
    const categories = Array.from(new Set(checkedCategories.concat(newCategories)));
    const tags = document.getElementById('tagsInput').value.split(',').map((s) => s.trim()).filter(Boolean);
    if (!title || !slug) {
      showMessage('タイトルとスラッグは必須です。', 'error');
      return;
    }
    LetsBlogLoading.begin({
      buttonIds: ['approveButton'],
      text: '記事のスキャフォールドを生成しています…',
      kind: 'load',
    });
    post('approveAndScaffold', { issue: selectedIssue, metadata: { title, slug, categories, tags } });
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = 'block';
  }

  // Escapeで実行中の処理を中断できるようにする。
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && LetsBlogLoading.isRunning()) {
      post('cancel');
    }
  });

  document.getElementById('issuePrevButton').addEventListener('click', () => changeIssuePage(-1));
  document.getElementById('issueNextButton').addEventListener('click', () => changeIssuePage(1));
  document.getElementById('sendButton').addEventListener('click', sendMessage);
  document.getElementById('chatInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') sendMessage();
  });
  document.getElementById('suggestStructureButton').addEventListener('click', requestStructureSuggestion);
  document.getElementById('acceptStructureButton').addEventListener('click', acceptStructure);
  document.getElementById('suggestButton').addEventListener('click', requestMetadataSuggestion);
  document.getElementById('approveButton').addEventListener('click', approveMetadata);

  window.addEventListener('message', (event) => {
    const { command, payload } = event.data;
    switch (command) {
      case 'issueList':
        LetsBlogLoading.end();
        renderIssueList(payload.issues);
        break;
      case 'categoryList':
        LetsBlogLoading.end();
        existingCategories = payload.categories || [];
        renderCategoryCheckboxes([]);
        break;
      case 'chatResponse':
        LetsBlogLoading.end();
        addMessage('assistant', payload.reply);
        sessionId = payload.sessionId;
        break;
      case 'structureSuggestion':
        LetsBlogLoading.end();
        document.getElementById('structureTextarea').value = payload.structure || '';
        document.getElementById('structureAcceptedBadge').style.display = 'none';
        break;
      case 'structureAccepted':
        LetsBlogLoading.end();
        const badge = document.getElementById('structureAcceptedBadge');
        badge.textContent = 'Issue #' + payload.issueNumber + ' の本文を更新しました。';
        badge.style.display = 'block';
        showMessage('記事構成をIssueへ反映しました。', 'success');
        break;
      case 'metadataSuggestion':
        LetsBlogLoading.end();
        showMetadataForm(payload);
        break;
      case 'scaffoldCreated':
        LetsBlogLoading.end();
        showMessage('記事のスキャフォールドを生成しました。', 'success');
        setTimeout(() => post('openArticle'), 500);
        break;
      case 'cancelled':
        LetsBlogLoading.end();
        showMessage('操作をキャンセルしました。', '');
        break;
      case 'error':
        LetsBlogLoading.end();
        showMessage(payload.error, 'error');
        break;
    }
  });

  LetsBlogLoading.begin({ text: 'Issueとカテゴリを取得しています…', kind: 'load' });
  post('loadIssues');
  post('loadCategories');
