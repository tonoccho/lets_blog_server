import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as api from './apiClient';
import { getActor, getServerUrl, requireApiKey } from './config';
import { parseArticle, stringifyArticle } from './frontMatter';
import { describeError } from './errorHandler';
import { buildScriptedCsp, createNonce } from './webviewSecurity';
import { logger } from './logger';

/**
 * VSCode拡張の「Let's Blog: Generate Image」用WebviewPanel。automatic1111相当のパラメータで
 * ComfyUI画像を生成し、生成結果を記事の「アイキャッチ」または「アセット」として組み込む。
 * WebviewPanelの骨格はplanPanel.ts(acquireVsCodeApi + postMessageディスパッチ)に準拠する。
 */
const ALLOWED_IMAGE_EXTENSIONS = ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp'];

export class ImageGenPanel {
  private static currentPanel: ImageGenPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;

  static createOrShow(
    context: vscode.ExtensionContext,
    editor: vscode.TextEditor,
    baseDir: string,
    projectId: number
  ): void {
    if (ImageGenPanel.currentPanel) {
      ImageGenPanel.currentPanel._panel.reveal(vscode.ViewColumn.Beside);
      return;
    }
    ImageGenPanel.currentPanel = new ImageGenPanel(context, editor, baseDir, projectId);
  }

  private constructor(
    private readonly _context: vscode.ExtensionContext,
    private readonly _editor: vscode.TextEditor,
    private readonly _baseDir: string,
    private readonly _projectId: number
  ) {
    this._panel = vscode.window.createWebviewPanel(
      'letsBlog.imageGen',
      'Generate Image',
      vscode.ViewColumn.Beside,
      { enableScripts: true, retainContextWhenHidden: true }
    );
    this._panel.onDidDispose(() => this.dispose(), null);
    this._panel.webview.onDidReceiveMessage((message) => this._handleMessage(message), null);
    this._panel.webview.html = this._getHtmlContent();
  }

  private dispose(): void {
    ImageGenPanel.currentPanel = undefined;
    this._panel.dispose();
  }

  private _sendMessage(command: string, payload: unknown): void {
    this._panel.webview.postMessage({ command, payload });
  }

  private async _handleMessage(message: { command: string; [key: string]: unknown }): Promise<void> {
    try {
      switch (message.command) {
        case 'loadOptions':
          await this._handleLoadOptions();
          break;
        case 'generate':
          await this._handleGenerate(message as unknown as GenerateMessage);
          break;
        case 'setAsEyecatch':
          await this._handleSetAsEyecatch(message as unknown as GeneratedImageMessage);
          break;
        case 'addAsAsset':
          await this._handleAddAsAsset(message as unknown as GeneratedImageMessage);
          break;
      }
    } catch (error) {
      const description = describeError(error);
      logger.error(`${this.constructor.name}: ${String(message.command)} に失敗しました: ${description}`, {
        stack: error instanceof Error ? error.stack : undefined,
      });
      this._sendMessage('error', { error: description });
    }
  }

  private async _handleLoadOptions(): Promise<void> {
    const apiKey = await requireApiKey(this._context);
    const options = await api.getImageGenerationOptions(getServerUrl(), apiKey, this._projectId);
    this._sendMessage('options', options);
  }

  private async _handleGenerate(message: GenerateMessage): Promise<void> {
    const apiKey = await requireApiKey(this._context);
    const actor = await getActor(this._context);
    const result = await api.generateImage(getServerUrl(), apiKey, actor, this._projectId, message.params);
    this._sendMessage('generated', result);
  }

  private async _handleSetAsEyecatch(message: GeneratedImageMessage): Promise<void> {
    const fileName = this._saveToAssets(message.imageData, message.fileName, 'eyecatch');

    const article = parseArticle(this._editor.document.getText());
    article.data.featured_image = `assets/${fileName}`;
    await this._replaceEditorText(stringifyArticle(article));

    this._sendMessage('eyecatchSet', { fileName });
    vscode.window.showInformationMessage(`アイキャッチを 'assets/${fileName}' に設定しました。`);
  }

  private async _handleAddAsAsset(message: GeneratedImageMessage): Promise<void> {
    const fileName = this._saveToAssets(message.imageData, message.fileName, 'generated-asset');

    const markdownImage = `![${message.prompt}](assets/${fileName})`;
    const edit = new vscode.WorkspaceEdit();
    edit.insert(this._editor.document.uri, this._editor.selection.active, markdownImage);
    await vscode.workspace.applyEdit(edit);
    await this._editor.document.save();

    this._sendMessage('assetAdded', { fileName });
    vscode.window.showInformationMessage(`アセットを 'assets/${fileName}' に追加しました。`);
  }

  /** Base64画像データを{baseDir}/assets配下へ保存し、生成したファイル名を返す。 */
  private _saveToAssets(imageDataBase64: string, sourceFileName: string, prefix: string): string {
    const assetsDir = path.join(this._baseDir, 'assets');
    fs.mkdirSync(assetsDir, { recursive: true });

    const decoded = Buffer.from(imageDataBase64, 'base64');
    // 拡張子はサーバー応答由来のため、既知の画像拡張子だけを採用する
    // (ファイル名自体は接頭辞とタイムスタンプから組み立てるため、パス要素は混入しない)。
    const candidate = path.extname(sourceFileName).toLowerCase();
    const extension = ALLOWED_IMAGE_EXTENSIONS.includes(candidate) ? candidate : '.png';
    const fileName = `${prefix}-${Date.now()}${extension}`;
    fs.writeFileSync(path.join(assetsDir, fileName), decoded);
    return fileName;
  }

  private async _replaceEditorText(newText: string): Promise<void> {
    const document = this._editor.document;
    const fullRange = new vscode.Range(
      document.positionAt(0),
      document.positionAt(document.getText().length)
    );
    const edit = new vscode.WorkspaceEdit();
    edit.replace(document.uri, fullRange, newText);
    await vscode.workspace.applyEdit(edit);
    await document.save();
  }

  private _getHtmlContent(): string {
    const nonce = createNonce();
    return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${buildScriptedCsp(nonce)}">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Generate Image</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: var(--vscode-font-family, sans-serif); padding: 16px; color: var(--vscode-foreground); }
  .container { max-width: 640px; }
  .section { margin-bottom: 20px; }
  .section-title { font-weight: bold; margin-bottom: 8px; }
  .form-group { margin-bottom: 10px; }
  .form-group label { display: block; margin-bottom: 4px; }
  .form-group input, .form-group select, .form-group textarea {
    width: 100%; padding: 6px; background: var(--vscode-input-background, #fff);
    color: var(--vscode-input-foreground, inherit); border: 1px solid var(--vscode-panel-border, #ccc);
  }
  .form-row { display: flex; gap: 10px; }
  .form-row .form-group { flex: 1; }
  textarea { min-height: 60px; font-family: inherit; }
  button { padding: 6px 14px; cursor: pointer; }
  button.primary { background: var(--vscode-button-background, #007acc); color: var(--vscode-button-foreground, #fff); border: none; }
  button:disabled { opacity: 0.6; cursor: default; }
  .preview-section { display: none; border: 1px solid var(--vscode-panel-border, #ccc); padding: 10px; }
  .preview-section img { max-width: 100%; display: block; margin-bottom: 8px; }
  .preview-info { font-size: 0.9em; opacity: 0.8; margin-bottom: 8px; white-space: pre-wrap; }
  .button-group { display: flex; gap: 10px; }
  #message { margin-top: 16px; display: none; }
  #message.error { color: var(--vscode-errorForeground, #d32f2f); }
  #message.success { color: #388e3c; }
  #loraWeightGroup { display: none; }
</style>
</head>
<body>
<div class="container">
  <div class="section">
    <div class="section-title">パラメータ</div>
    <div class="form-group">
      <label>prompt</label>
      <textarea id="prompt" placeholder="生成したい画像の説明"></textarea>
    </div>
    <div class="form-group">
      <label>negative prompt</label>
      <textarea id="negativePrompt" placeholder="low quality, blurry, watermark, text"></textarea>
    </div>
    <div class="form-row">
      <div class="form-group">
        <label>steps</label>
        <input type="number" id="steps" min="1" max="150" value="20">
      </div>
      <div class="form-group">
        <label>cfg scale</label>
        <input type="number" id="cfgScale" min="0" max="30" step="0.1" value="7.0">
      </div>
      <div class="form-group">
        <label>seed(空欄でランダム)</label>
        <input type="text" id="seed" placeholder="">
      </div>
    </div>
    <div class="form-row">
      <div class="form-group">
        <label>sampler</label>
        <select id="samplerName"></select>
      </div>
      <div class="form-group">
        <label>scheduler</label>
        <select id="scheduler"></select>
      </div>
    </div>
    <div class="form-row">
      <div class="form-group">
        <label>width</label>
        <input type="number" id="width" min="64" max="2048" step="8" value="512">
      </div>
      <div class="form-group">
        <label>height</label>
        <input type="number" id="height" min="64" max="2048" step="8" value="512">
      </div>
      <div class="form-group">
        <label>batch size</label>
        <input type="number" id="batchSize" min="1" max="4" value="1">
      </div>
    </div>
    <div class="form-group">
      <label>checkpoint</label>
      <select id="checkpoint"></select>
    </div>
    <div class="form-row">
      <div class="form-group">
        <label>LoRA</label>
        <select id="loraName"><option value="">なし</option></select>
      </div>
      <div class="form-group" id="loraWeightGroup">
        <label>LoRA weight</label>
        <input type="number" id="loraWeight" min="0" max="2" step="0.1" value="1.0">
      </div>
    </div>
    <button id="generateButton" class="primary">生成</button>
  </div>

  <div class="section preview-section" id="previewSection">
    <div class="section-title">プレビュー</div>
    <img id="previewImage" alt="生成画像プレビュー">
    <div class="preview-info" id="previewInfo"></div>
    <div class="button-group">
      <button id="setAsEyecatchButton" class="primary">アイキャッチとして設定</button>
      <button id="addAsAssetButton">アセットとして追加</button>
    </div>
  </div>

  <div id="message"></div>
</div>

<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  let currentImage = null;
  let currentPrompt = '';

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
    currentPrompt = params.prompt;
    document.getElementById('generateButton').disabled = true;
    showMessage('生成しています…', '');
    post('generate', { params });
  }

  function renderGenerated(result) {
    currentImage = result;
    document.getElementById('generateButton').disabled = false;
    document.getElementById('previewSection').style.display = 'block';
    // mimeTypeはサーバー応答由来のため、既知の画像種別だけをデータURIへ組み立てる。
    const safeMimeType = /^image\\/(png|jpeg|gif|webp|bmp|svg\\+xml)$/.test(result.mimeType || '')
      ? result.mimeType
      : 'image/png';
    document.getElementById('previewImage').src = 'data:' + safeMimeType + ';base64,' + result.dataBase64;
    document.getElementById('previewInfo').textContent =
      'ファイル名: ' + result.fileName + '\\n生成時刻: ' + new Date().toLocaleString();
    showMessage('生成しました。', 'success');
  }

  function setAsEyecatch() {
    if (!currentImage) return;
    post('setAsEyecatch', { imageData: currentImage.dataBase64, fileName: currentImage.fileName, prompt: currentPrompt });
  }

  function addAsAsset() {
    if (!currentImage) return;
    post('addAsAsset', { imageData: currentImage.dataBase64, fileName: currentImage.fileName, prompt: currentPrompt });
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
</script>
</body>
</html>`;
  }
}

interface GenerateMessage {
  command: 'generate';
  params: api.ImageGenerationParams;
}

interface GeneratedImageMessage {
  command: 'setAsEyecatch' | 'addAsAsset';
  imageData: string;
  fileName: string;
  prompt: string;
}
