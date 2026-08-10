  const vscode = acquireVsCodeApi();
  let selectedIssue = null;
  let sessionId = undefined;
  let chatHistory = [];
  let existingCategories = [];

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  function renderIssueList(issues) {
    const el = document.getElementById('issueList');
    el.innerHTML = '';
    if (issues.length === 0) {
      el.innerHTML = '<div class="empty">未割り当てのissueはありません。</div>';
      return;
    }
    issues.forEach((issue) => {
      const item = document.createElement('div');
      item.className = 'issue-item';
      item.textContent = '#' + issue.number + ': ' + issue.title;
      item.addEventListener('click', () => selectIssue(issue, item));
      el.appendChild(item);
    });
  }

  function selectIssue(issue, el) {
    selectedIssue = issue;
    sessionId = undefined;
    chatHistory = [];
    document.getElementById('messages').innerHTML = '';
    document.querySelectorAll('.issue-item').forEach((n) => n.classList.remove('selected'));
    el.classList.add('selected');
    document.getElementById('chatSection').style.display = 'block';
    document.getElementById('structureSection').style.display = 'block';
    document.getElementById('structureTextarea').value = '';
    document.getElementById('structureAcceptedBadge').style.display = 'none';
    document.getElementById('metadataSection').style.display = 'none';
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
    if (!text || !selectedIssue) return;
    addMessage('user', text);
    input.value = '';
    post('sendChat', { history: chatHistory.slice(0, -1), message: text, sessionId, issueNumber: selectedIssue.number });
  }

  function requestStructureSuggestion() {
    if (!selectedIssue || chatHistory.length === 0) {
      showMessage('先にチャットで壁打ちしてください。', 'error');
      return;
    }
    post('suggestStructure', { history: chatHistory });
  }

  function acceptStructure() {
    const structure = document.getElementById('structureTextarea').value.trim();
    if (!selectedIssue || !structure) {
      showMessage('構成案が空です。', 'error');
      return;
    }
    post('acceptStructure', { issueNumber: selectedIssue.number, structure });
  }

  function requestMetadataSuggestion() {
    post('suggestMetadata', { history: chatHistory });
  }

  function renderCategoryCheckboxes(selected) {
    const container = document.getElementById('categoryCheckboxes');
    container.innerHTML = '';
    if (existingCategories.length === 0) {
      container.innerHTML = '<span class="hint">既存カテゴリを取得できませんでした(サイト未紐付け等)。新規カテゴリ欄に直接入力してください。</span>';
      return;
    }
    existingCategories.forEach((name) => {
      const label = document.createElement('label');
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.value = name;
      checkbox.checked = (selected || []).some((s) => s.toLowerCase() === name.toLowerCase());
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
    post('approveAndScaffold', { issue: selectedIssue, metadata: { title, slug, categories, tags } });
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = 'block';
  }

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
        renderIssueList(payload.issues);
        break;
      case 'categoryList':
        existingCategories = payload.categories || [];
        renderCategoryCheckboxes([]);
        break;
      case 'chatResponse':
        addMessage('assistant', payload.reply);
        sessionId = payload.sessionId;
        break;
      case 'structureSuggestion':
        document.getElementById('structureTextarea').value = payload.structure || '';
        document.getElementById('structureAcceptedBadge').style.display = 'none';
        break;
      case 'structureAccepted':
        const badge = document.getElementById('structureAcceptedBadge');
        badge.textContent = 'Issue #' + payload.issueNumber + ' の本文を更新しました。';
        badge.style.display = 'block';
        showMessage('記事構成をIssueへ反映しました。', 'success');
        break;
      case 'metadataSuggestion':
        showMetadataForm(payload);
        break;
      case 'scaffoldCreated':
        showMessage('記事のスキャフォールドを生成しました。', 'success');
        setTimeout(() => post('openArticle'), 500);
        break;
      case 'error':
        showMessage(payload.error, 'error');
        break;
    }
  });

  post('loadIssues');
  post('loadCategories');
