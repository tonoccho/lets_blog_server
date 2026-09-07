import { setConfiguration, resetMocks } from '../__mocks__/vscode';
import * as apiClient from '../apiClient';
import { httpRequest } from '../httpClient';
import { TimeoutError } from '../errorHandler';

jest.mock('../httpClient', () => ({ httpRequest: jest.fn() }));

const mockedRequest = httpRequest as jest.MockedFunction<typeof httpRequest>;

/**
 * 画像生成(POST /api/ai/image)のクライアント側タイムアウト(issue #1105)。
 *
 * 既定の120秒は batch size 16 / batch count 16(最大256枚)の生成では確実に足りず、
 * サーバー側では生成が続いて`generated_images`へ保存されるのに、クライアントだけが
 * `TimeoutError`になっていた。サーバーが自分へ課している予算
 * (media: 300秒 + batchCount × max(120, 60 + 8×batchSize) 秒)を下回らず、
 * かつ nginx の 3600 秒(これを超えても先にnginxが切るので無意味)を超えないことを固定する。
 */
describe('画像生成が最低限確保するタイムアウト', () => {
  /** media側の`NON_POLLING_OVERHEAD_SECONDS`(300秒)。 */
  const OVERHEAD_MS = 300_000;
  /** media側の`ComfyUiClient.maxPollSeconds(16)`(188秒)。 */
  const MAX_POLL_16_MS = 188_000;
  /** nginx の `location = /api/ai/image` の proxy_read_timeout。 */
  const NGINX_TIMEOUT_MS = 3_600_000;

  it('枚数未指定でも1枚ぶんの予算(300秒+120秒)を確保する', () => {
    expect(apiClient.imageGenerationMinTimeoutMs({ prompt: '猫' })).toBe(OVERHEAD_MS + 120_000);
  });

  it('batch sizeに比例して伸びる', () => {
    expect(apiClient.imageGenerationMinTimeoutMs({ prompt: '猫', batchSize: 16 })).toBe(
      OVERHEAD_MS + MAX_POLL_16_MS
    );
  });

  it('batch countの回数ぶん繰り返す前提で伸びる', () => {
    expect(apiClient.imageGenerationMinTimeoutMs({ prompt: '猫', batchSize: 16, batchCount: 16 })).toBe(
      OVERHEAD_MS + MAX_POLL_16_MS * 16
    );
  });

  it('最大枚数(256枚)でもサーバー側の最悪ケース(3308秒)を下回らない', () => {
    const mediaWorstCaseMs = 3_308_000;

    expect(
      apiClient.imageGenerationMinTimeoutMs({ prompt: '猫', batchSize: 16, batchCount: 16 })
    ).toBeGreaterThanOrEqual(mediaWorstCaseMs);
  });

  it('0以下の枚数は1枚として扱う', () => {
    expect(apiClient.imageGenerationMinTimeoutMs({ prompt: '猫', batchSize: 0, batchCount: -3 })).toBe(
      OVERHEAD_MS + 120_000
    );
  });

  it('nginxのタイムアウトを超えない(超えても先にnginxが切るため意味が無い)', () => {
    expect(
      apiClient.imageGenerationMinTimeoutMs({ prompt: '猫', batchSize: 100, batchCount: 100 })
    ).toBe(NGINX_TIMEOUT_MS);
  });
});

describe('画像生成リクエストの実際のタイムアウト', () => {
  /** 応答を返さないサーバー。中断シグナルだけを観測する。 */
  function neverResponds(): void {
    mockedRequest.mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options.signal?.addEventListener('abort', () => reject(new Error('aborted')));
        })
    );
  }

  /** 保留中かどうかを観測できる形で待つ。 */
  function track(promise: Promise<unknown>): { settled: boolean; error: unknown } {
    const state: { settled: boolean; error: unknown } = { settled: false, error: undefined };
    promise.then(
      () => {
        state.settled = true;
      },
      (error) => {
        state.settled = true;
        state.error = error;
      }
    );
    return state;
  }

  /**
   * 偽タイマーを進め、中断がリクエスト層を伝わり切るまでマイクロタスクを流す。
   * httpRequest → request → withRetry → requestJson と await が重なるため、1回では足りない。
   */
  async function advance(ms: number): Promise<void> {
    jest.advanceTimersByTime(ms);
    for (let i = 0; i < 20; i += 1) {
      await Promise.resolve();
    }
  }

  beforeEach(() => {
    jest.useFakeTimers();
    mockedRequest.mockReset();
    resetMocks();
    apiClient.clearResponseCache();
    setConfiguration('letsBlog.serverUrl', 'https://stack.test');
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('既定設定のままでも batch size 16 の生成が120秒で打ち切られない', async () => {
    neverResponds();
    const state = track(
      apiClient.generateImage('token', undefined, 7, { prompt: '猫', batchSize: 16 }).catch((e) => {
        throw e;
      })
    );

    await advance(120_000);
    expect(state.settled).toBe(false);

    await advance(400_000);
    expect(state.settled).toBe(true);
    expect(state.error).toBeInstanceOf(TimeoutError);
  });

  it('利用者設定が計算値より長ければ利用者設定を優先する', async () => {
    setConfiguration('letsBlog.requestTimeoutMs', 5_000_000);
    neverResponds();
    const state = track(apiClient.generateImage('token', undefined, 7, { prompt: '猫' }));

    await advance(3_600_000);
    expect(state.settled).toBe(false);

    await advance(1_500_000);
    expect(state.settled).toBe(true);
  });
});
