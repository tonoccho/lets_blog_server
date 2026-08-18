(function () {
  const vscode = acquireVsCodeApi();

  const EMPTY_MXGRAPH_XML =
    '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/></root></mxGraphModel>';

  let mode = 'create';
  let pendingLoadXml = '';
  let latestXml = '';
  let drawioReady = false;
  /** ボタン押下で確定させたい保存アクション('insertNew'|'saveOverwrite'|'saveAsNew')。exportの応答を待っている間だけ設定する。 */
  let pendingAction = null;

  const frame = document.getElementById('drawioFrame');

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = text ? 'block' : 'none';
  }

  function postToDrawio(action) {
    frame.contentWindow.postMessage(JSON.stringify(action), '*');
  }

  function setButtonsEnabled(enabled) {
    ['insertButton', 'overwriteButton', 'saveAsNewButton', 'cancelButton'].forEach(function (id) {
      document.getElementById(id).disabled = !enabled;
    });
  }

  function requestExport(action) {
    if (!drawioReady) {
      showMessage('draw.ioエディタの準備ができていません。しばらく待ってから再試行してください。', 'error');
      return;
    }
    pendingAction = action;
    setButtonsEnabled(false);
    showMessage('エクスポート中…', '');
    postToDrawio({ action: 'export', format: 'xmlsvg', spin: 'エクスポート中…' });
  }

  function decodeSvgFromExport(data) {
    // data.data は "data:image/svg+xml;base64,...." 形式。
    const marker = 'base64,';
    const index = data.data.indexOf(marker);
    const base64 = index >= 0 ? data.data.slice(index + marker.length) : data.data;
    return atob(base64);
  }

  function handleExportResult(data) {
    const svg = decodeSvgFromExport(data);
    const xml = data.xml || latestXml;
    const name = document.getElementById('nameInput').value.trim() || '無題のダイアグラム';
    const action = pendingAction;
    pendingAction = null;

    if (action === 'insertNew') {
      post('insertNew', { name, xml, svg });
    } else if (action === 'saveOverwrite') {
      post('saveOverwrite', { xml, svg });
    } else if (action === 'saveAsNew') {
      post('saveAsNew', { name, xml, svg });
    }
  }

  window.addEventListener('message', function (event) {
    const msg = event.data;

    // 拡張(extension host)からのメッセージはオブジェクト、draw.io iframeからはJSON文字列で届く。
    if (msg && typeof msg === 'object' && msg.command) {
      if (msg.command === 'init') {
        const payload = msg.payload || {};
        mode = payload.mode;
        document.getElementById('nameInput').value = payload.name || '';
        pendingLoadXml = payload.xml || '';
        document.getElementById('insertButton').style.display = mode === 'create' ? 'inline-block' : 'none';
        document.getElementById('overwriteButton').style.display = mode === 'edit' ? 'inline-block' : 'none';
        document.getElementById('saveAsNewButton').style.display = mode === 'edit' ? 'inline-block' : 'none';
        frame.src = payload.drawioUrl;
      } else if (msg.command === 'error') {
        setButtonsEnabled(true);
        showMessage((msg.payload && msg.payload.error) || 'エラーが発生しました。', 'error');
      }
      return;
    }

    if (typeof msg !== 'string') return;
    let data;
    try {
      data = JSON.parse(msg);
    } catch (e) {
      return;
    }

    if (data.event === 'init') {
      drawioReady = true;
      postToDrawio({ action: 'load', xml: pendingLoadXml || EMPTY_MXGRAPH_XML, autosave: 1 });
      return;
    }
    if (data.event === 'autosave' || data.event === 'save') {
      latestXml = data.xml || latestXml;
      return;
    }
    if (data.event === 'export') {
      handleExportResult(data);
      return;
    }
  });

  document.getElementById('insertButton').addEventListener('click', function () {
    requestExport('insertNew');
  });
  document.getElementById('overwriteButton').addEventListener('click', function () {
    requestExport('saveOverwrite');
  });
  document.getElementById('saveAsNewButton').addEventListener('click', function () {
    requestExport('saveAsNew');
  });
  document.getElementById('cancelButton').addEventListener('click', function () {
    post('cancel');
  });

  post('ready');
})();
