/**
 * webviews/配下の素のJavaScriptを、ブラウザ無しでjestから実行するための最小DOM(issue #1104)。
 *
 * Webviewのスクリプトは拡張ホストの中でしか動かないため、これまで自動テストが無く、
 * 挙動は apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.md だけが担保していた。
 * ここでは jsdom を持ち込まず、スクリプトが実際に使うAPI
 * (getElementById / createElement / appendChild / addEventListener など)だけを再現する。
 *
 * 要素のidは**実際のHTMLから読み取る**。HTML側に無いidを参照すると undefined になり、
 * テストが落ちる。HTMLとスクリプトの食い違いをテストで検出できるようにするため。
 */

import * as fs from 'fs';
import * as path from 'path';

type Listener = (event: FakeEvent) => void;

export interface FakeEvent {
  type: string;
  key?: string;
  ctrlKey?: boolean;
  metaKey?: boolean;
  target?: FakeElement;
  data?: unknown;
  preventDefault?: () => void;
  stopPropagation?: () => void;
}

export class FakeElement {
  public id = '';
  public type = '';
  public className = '';
  public textContent = '';
  public value = '';
  public placeholder = '';
  public src = '';
  public alt = '';
  public disabled = false;
  public selected = false;
  public scrollTop = 0;
  public scrollHeight = 0;
  public focused = false;
  public readonly style: Record<string, string> = {};
  public readonly attributes: Record<string, string> = {};
  public readonly children: FakeElement[] = [];
  private readonly listeners = new Map<string, Listener[]>();

  constructor(public readonly tagName: string) {}

  get firstChild(): FakeElement | undefined {
    return this.children[0];
  }

  /** 実装が使うのは「子を消す」用途と、素朴な単一optionの差し込みだけ。 */
  set innerHTML(html: string) {
    this.children.length = 0;
    const match = /<(\w+)[^>]*>([^<]*)<\/\1>/.exec(html);
    if (match) {
      const child = new FakeElement(match[1]);
      child.textContent = match[2];
      this.children.push(child);
    }
  }

  get innerHTML(): string {
    return this.children.map((c) => c.textContent).join('');
  }

  appendChild(child: FakeElement): FakeElement {
    this.children.push(child);
    return child;
  }

  setAttribute(name: string, value: string): void {
    this.attributes[name] = value;
  }

  getAttribute(name: string): string | null {
    return this.attributes[name] ?? null;
  }

  addEventListener(type: string, listener: Listener): void {
    const existing = this.listeners.get(type) ?? [];
    existing.push(listener);
    this.listeners.set(type, existing);
  }

  dispatchEvent(event: FakeEvent): void {
    for (const listener of this.listeners.get(event.type) ?? []) {
      listener({ target: this, preventDefault: () => undefined, stopPropagation: () => undefined, ...event });
    }
  }

  click(): void {
    this.dispatchEvent({ type: 'click' });
  }

  focus(): void {
    this.focused = true;
  }
}

export interface WebviewHarness {
  /** idで要素を取り出す(HTMLに無いidはundefined)。 */
  element(id: string): FakeElement;
  /** 拡張側からWebviewへメッセージを送った状況を再現する。 */
  postToWebview(message: { command: string; payload?: unknown }): void;
  /** Webviewが拡張側へ送ったメッセージ。 */
  readonly posted: Record<string, unknown>[];
  /** LetsBlogLoading の呼び出し記録。 */
  readonly loading: { running: boolean; begins: unknown[]; ends: number };
  /** documentレベルのキー入力を再現する。 */
  pressKey(event: Partial<FakeEvent> & { key: string }): void;
}

/**
 * webviews/<name>.html からidを収集し、webviews/<name>.js をその上で実行する。
 */
export function loadWebview(name: string): WebviewHarness {
  const webviewsDir = path.resolve(__dirname, '..', '..', '..', 'webviews');
  const html = fs.readFileSync(path.join(webviewsDir, `${name}.html`), 'utf-8');
  const source = fs.readFileSync(path.join(webviewsDir, `${name}.js`), 'utf-8');

  const elements = new Map<string, FakeElement>();
  for (const match of html.matchAll(/\bid="([^"]+)"/g)) {
    const element = new FakeElement('div');
    element.id = match[1];
    elements.set(match[1], element);
  }

  const documentListeners = new Map<string, Listener[]>();
  const windowListeners = new Map<string, Listener[]>();
  const posted: Record<string, unknown>[] = [];
  const loading = { running: false, begins: [] as unknown[], ends: 0 };

  const addListener = (map: Map<string, Listener[]>, type: string, listener: Listener): void => {
    const existing = map.get(type) ?? [];
    existing.push(listener);
    map.set(type, existing);
  };

  const fakeDocument = {
    getElementById: (id: string): FakeElement | undefined => elements.get(id),
    createElement: (tagName: string): FakeElement => new FakeElement(tagName),
    addEventListener: (type: string, listener: Listener): void => addListener(documentListeners, type, listener),
  };

  const fakeWindow = {
    addEventListener: (type: string, listener: Listener): void => addListener(windowListeners, type, listener),
  };

  const letsBlogLoading = {
    begin: (options: unknown): void => {
      loading.running = true;
      loading.begins.push(options);
    },
    end: (): void => {
      loading.running = false;
      loading.ends += 1;
    },
    isRunning: (): boolean => loading.running,
  };

  class FakeEventCtor {
    constructor(public readonly type: string) {}
  }

  const run = new Function(
    'acquireVsCodeApi',
    'document',
    'window',
    'LetsBlogLoading',
    'Event',
    source
  );
  run(
    () => ({ postMessage: (message: Record<string, unknown>) => posted.push(message) }),
    fakeDocument,
    fakeWindow,
    letsBlogLoading,
    FakeEventCtor
  );

  return {
    element: (id: string): FakeElement => elements.get(id) as FakeElement,
    postToWebview: (message): void => {
      for (const listener of windowListeners.get('message') ?? []) {
        listener({ type: 'message', data: message });
      }
    },
    posted,
    loading,
    pressKey: (event): void => {
      for (const listener of documentListeners.get('keydown') ?? []) {
        listener({ type: 'keydown', preventDefault: () => undefined, stopPropagation: () => undefined, ...event });
      }
    },
  };
}
