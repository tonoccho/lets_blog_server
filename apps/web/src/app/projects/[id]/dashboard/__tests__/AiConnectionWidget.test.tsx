import { render, screen, within } from '@testing-library/react';
import type { AiConnection } from '@/lib/apiClient';

const listAiConnections = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  listAiConnections: (...a: unknown[]) => listAiConnections(...a),
}));

import { AiConnectionWidget, AiConnectionWidgetFallback, AiConnectionWidgetView } from '../AiConnectionWidget';

const conn = (over: Partial<AiConnection> & Pick<AiConnection, 'provider'>): AiConnection => ({
  displayName: over.provider,
  targetUrl: null,
  source: 'PROJECT',
  status: 'NORMAL',
  detail: null,
  configured: true,
  ...over,
});

const all = (over: Partial<Record<AiConnection['provider'], Partial<AiConnection>>> = {}): AiConnection[] => [
  conn({ provider: 'OLLAMA', displayName: 'Ollama', targetUrl: 'http://ollama:11434', ...over.OLLAMA }),
  conn({ provider: 'COMFYUI', displayName: 'ComfyUI', targetUrl: 'http://comfy:8188', ...over.COMFYUI }),
  conn({ provider: 'OPENAI', displayName: 'ChatGPT', ...over.OPENAI }),
  conn({ provider: 'CLAUDE', displayName: 'Claude', ...over.CLAUDE }),
];

const row = (name: string) => screen.getByRole('listitem', { name });
const HREF = '/projects/7?tab=settings';

describe('AiConnectionWidgetView(issue #1501)', () => {
  it('4行を表示名・バッジ・接続先情報つきで描く', () => {
    render(<AiConnectionWidgetView projectId={7} connections={all()} failed={false} />);

    expect(screen.getByRole('heading', { name: 'AI接続状況' })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(4);
    expect(row('Ollama')).toHaveTextContent('http://ollama:11434');
    expect(row('ComfyUI')).toHaveTextContent('http://comfy:8188');
    expect(row('ChatGPT')).toHaveTextContent('APIキー設定済み');
    expect(row('Claude')).toHaveTextContent('APIキー設定済み');
    expect(within(row('Ollama')).getByText('利用可能')).toBeInTheDocument();
  });

  it('configured=falseは利用不可で、4行とも ai-models タブへのリンクを出す', () => {
    const conns = all({
      OLLAMA: { configured: false, targetUrl: null },
      COMFYUI: { configured: false, targetUrl: null },
      OPENAI: { configured: false },
      CLAUDE: { configured: false },
    });
    render(<AiConnectionWidgetView projectId={7} connections={conns} failed={false} />);

    for (const name of ['Ollama', 'ComfyUI', 'ChatGPT', 'Claude']) {
      expect(within(row(name)).getByText('利用不可')).toBeInTheDocument();
      expect(within(row(name)).getByRole('link')).toHaveAttribute('href', HREF);
    }
    expect(row('Ollama')).toHaveTextContent('未設定');
    expect(row('ChatGPT')).toHaveTextContent('APIキー未設定');
    expect(row('Claude')).toHaveTextContent('APIキー未設定');
  });

  it('configured=trueでもstatus=ERRORは利用不可でリンクを出す', () => {
    render(<AiConnectionWidgetView projectId={7} connections={all({ OLLAMA: { status: 'ERROR' } })} failed={false} />);

    expect(within(row('Ollama')).getByText('利用不可')).toBeInTheDocument();
    expect(within(row('Ollama')).getByRole('link')).toHaveAttribute('href', HREF);
  });

  it('利用可能の行(NORMAL / WARNING)にはリンクを出さない', () => {
    render(<AiConnectionWidgetView projectId={7} connections={all({ COMFYUI: { status: 'WARNING' } })} failed={false} />);

    expect(within(row('ComfyUI')).getByText('利用可能')).toBeInTheDocument();
    expect(within(row('Ollama')).getByText('利用可能')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('APIが1件欠いても定数の4行を維持し、欠けた行は利用不可にする', () => {
    render(<AiConnectionWidgetView projectId={7} connections={all().slice(0, 3)} failed={false} />);

    expect(screen.getAllByRole('listitem')).toHaveLength(4);
    expect(within(row('Claude')).getByText('利用不可')).toBeInTheDocument();
    expect(within(row('Claude')).getByRole('link')).toHaveAttribute('href', HREF);
  });

  it('取得失敗時は4行ではなく失敗表示を出す', () => {
    render(<AiConnectionWidgetView projectId={7} connections={[]} failed />);

    expect(screen.getByRole('alert')).toHaveTextContent('AI接続状況を取得できませんでした');
    expect(screen.queryByRole('listitem', { name: 'Ollama' })).not.toBeInTheDocument();
    expect(screen.queryByText('利用不可')).not.toBeInTheDocument();
  });
});

describe('AiConnectionWidget(取得)', () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    listAiConnections.mockReset();
    errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => errorSpy.mockRestore());

  it('listAiConnections(projectId)の結果を描く', async () => {
    listAiConnections.mockResolvedValue(all());

    render(await AiConnectionWidget({ projectId: 7 }));

    expect(listAiConnections).toHaveBeenCalledWith(7);
    expect(screen.getAllByRole('listitem')).toHaveLength(4);
  });

  it('取得に失敗したら記録し、失敗表示を出す', async () => {
    listAiConnections.mockRejectedValue(new Error('down'));

    render(await AiConnectionWidget({ projectId: 7 }));

    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('AI接続状況'), expect.any(Error));
    expect(screen.getByRole('alert')).toHaveTextContent('AI接続状況を取得できませんでした');
  });
});

describe('AiConnectionWidgetFallback', () => {
  it('見出しと読み込み中の表示を出す', () => {
    render(<AiConnectionWidgetFallback />);

    expect(screen.getByRole('heading', { name: 'AI接続状況' })).toBeInTheDocument();
    expect(screen.getByText('読み込み中…')).toBeInTheDocument();
  });
});
