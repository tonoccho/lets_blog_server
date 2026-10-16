import { Logger, LogLevel, logger, redactContext } from '../logger';
import { outputChannels, resetMocks, setConfiguration } from '../__mocks__/vscode';

beforeEach(() => {
  resetMocks();
});

/** 出力先をメモリ上の行に差し替えたロガー(出力先の抽象 LogSink を満たす手書きの受け口)。 */
function loggerWithSink(): { instance: Logger; lines: string[] } {
  const instance = new Logger();
  const lines: string[] = [];
  instance.setSink({ appendLine: (line) => lines.push(line) });
  return { instance, lines };
}

describe('Logger のレベル制御', () => {
  it('既定ではINFO以上だけを出し、DEBUGは出さない', () => {
    const { instance, lines } = loggerWithSink();

    instance.debug('d');
    instance.info('i');
    instance.warn('w');
    instance.error('e');

    expect(lines.map((l) => /\[(\w+)\]/.exec(l.replace(/^\[[^\]]*\] /, ''))?.[1])).toEqual(['INFO', 'WARN', 'ERROR']);
  });

  it('setMinLevelでDEBUGを出せる', () => {
    const { instance, lines } = loggerWithSink();
    instance.setMinLevel(LogLevel.Debug);

    instance.debug('詳細');

    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatch(/^\[\d{4}-\d{2}-\d{2}T[^\]]+\] \[DEBUG\] 詳細$/);
  });

  it('letsBlog.debugModeが有効ならDEBUGを出し、無効ならINFO以上に戻る', () => {
    const { instance, lines } = loggerWithSink();
    setConfiguration('letsBlog.debugMode', true);
    instance.refreshFromConfiguration();
    instance.debug('on');
    setConfiguration('letsBlog.debugMode', false);
    instance.refreshFromConfiguration();
    instance.debug('off');

    expect(lines).toHaveLength(1);
    expect(lines[0]).toContain('on');
  });
});

describe('Logger のコンテキスト', () => {
  it('コンテキストはJSONで末尾に付き、認証情報らしいキーの値は伏せる', () => {
    const { instance, lines } = loggerWithSink();

    instance.info('呼び出し', { accessToken: 'secret-value', status: 200 });

    expect(lines[0]).toContain('{"accessToken":"***","status":200}');
    expect(lines[0]).not.toContain('secret-value');
  });

  it('空のコンテキストでは何も付けない', () => {
    const { instance, lines } = loggerWithSink();

    instance.info('呼び出し', {});

    expect(lines[0]).toMatch(/\] 呼び出し$/);
  });

  it('JSON化できないコンテキストでもログ自体は落ちない', () => {
    const { instance, lines } = loggerWithSink();
    const circular: Record<string, unknown> = {};
    circular.self = circular;

    instance.info('循環', circular);

    expect(lines[0]).toContain('[unserializable context]');
  });

  it('redactContextは認証情報らしいキーだけを伏せる', () => {
    expect(redactContext({ password: 'p', apiKey: 'k', Authorization: 'a', name: 'n' })).toEqual({
      password: '***',
      apiKey: '***',
      Authorization: '***',
      name: 'n',
    });
  });
});

describe('Logger の出力先(OutputChannel)', () => {
  it('出力先を指定しなければ「Let\'s Blog」の出力チャンネルを作って書き、show/disposeがそれに届く', () => {
    const instance = new Logger();

    instance.info('最初のログ');
    instance.info('二つ目のログ');
    instance.show();
    instance.dispose();

    expect(outputChannels).toHaveLength(1);
    expect(outputChannels[0].name).toBe("Let's Blog");
    expect(outputChannels[0].lines).toHaveLength(2);
    expect(outputChannels[0].shown).toBe(1);
    expect(outputChannels[0].disposed).toBe(true);
  });

  it('showとdisposeは、対応していない出力先でも例外にしない', () => {
    const instance = new Logger();
    instance.setSink({ appendLine: () => undefined });

    expect(() => instance.show()).not.toThrow();
    expect(() => instance.dispose()).not.toThrow();
  });

  it('出力先を作る前のdisposeは何もしない', () => {
    expect(() => new Logger().dispose()).not.toThrow();
    expect(outputChannels).toHaveLength(0);
  });

  it('共有のloggerはLoggerのインスタンスである', () => {
    expect(logger).toBeInstanceOf(Logger);
  });
});
