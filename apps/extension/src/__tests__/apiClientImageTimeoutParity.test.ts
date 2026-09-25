import { readFileSync } from 'fs';
import { resolve } from 'path';
import * as apiClient from '../apiClient';

/**
 * 画像生成のクライアント側タイムアウト予算(`apiClient.imageGenerationMinTimeoutMs`)が、
 * media-service と nginx の実際の値とズレていないことを固定する(issue #1118)。
 *
 * 拡張側の定数はJavaの定数を手で書き写したものなので、media側のソースと
 * nginx設定をテキストとして読み、同じ式で期待値を導いて突き合わせる
 * (`ImageGenerationTimeoutChainTest`がnginx設定を読むのと同じ手法)。
 * 失敗したら、media/nginx側の変更に合わせて`apiClient.ts`の`IMAGE_GEN_*`を更新すること。
 */
const REPO_ROOT = resolve(__dirname, '../../../..');

function read(relative: string): string {
  return readFileSync(resolve(REPO_ROOT, relative), 'utf8');
}

function intConstant(source: string, name: string, file: string): number {
  const match = new RegExp(`\\b${name}\\s*=\\s*(\\d+)\\s*;`).exec(source);
  if (!match) {
    throw new Error(`${file} から ${name} を読み取れない。定数の宣言形式が変わった場合はこのテストの抽出も直すこと`);
  }
  return Number(match[1]);
}

const COMFY_FILE = 'services/media/src/main/java/com/letsblog/media/ai/ComfyUiClient.java';
const CHAIN_TEST_FILE =
  'services/media/src/test/java/com/letsblog/media/ai/ImageGenerationTimeoutChainTest.java';
const NGINX_FILE = 'infra/nginx/conf.d/default.conf';

function nginxImageTimeoutMs(): number {
  const conf = read(NGINX_FILE);
  const block = /location\s*=\s*\/api\/ai\/image\s*\{([^}]*)\}/.exec(conf);
  if (!block) {
    throw new Error(`${NGINX_FILE} に location = /api/ai/image が見つからない`);
  }
  const timeout = /proxy_read_timeout\s+(\d+)s\s*;/.exec(block[1]);
  if (!timeout) {
    throw new Error(`${NGINX_FILE} の /api/ai/image に proxy_read_timeout(秒)が無い`);
  }
  return Number(timeout[1]) * 1000;
}

describe('画像生成タイムアウト予算がmedia/nginxの実値と一致する', () => {
  const comfy = read(COMFY_FILE);
  const chain = read(CHAIN_TEST_FILE);
  const pollIntervalMs = intConstant(comfy, 'POLL_INTERVAL_MS', COMFY_FILE);
  const base = intConstant(comfy, 'BASE_POLL_ATTEMPTS', COMFY_FILE);
  const perImage = intConstant(comfy, 'POLL_ATTEMPTS_PER_IMAGE', COMFY_FILE);
  const min = intConstant(comfy, 'MIN_POLL_ATTEMPTS', COMFY_FILE);
  const overheadMs = intConstant(chain, 'NON_POLLING_OVERHEAD_SECONDS', CHAIN_TEST_FILE) * 1000;
  const nginxMs = nginxImageTimeoutMs();

  /** media側の式そのもの: overhead + batchCount × max(MIN, BASE + PER_IMAGE × batchSize)。 */
  function expectedMs(batchSize: number, batchCount: number): number {
    const perRepeat = Math.max(min, base + perImage * batchSize) * pollIntervalMs;
    return Math.min(nginxMs, overheadMs + perRepeat * batchCount);
  }

  const sizes = [1, 2, 7, 8, 9, 16];
  const counts = [1, 2, 16];
  for (const size of sizes) {
    for (const count of counts) {
      it(`batchSize=${size} batchCount=${count} でmedia側の予算と一致する`, () => {
        expect(
          apiClient.imageGenerationMinTimeoutMs({ prompt: '猫', batchSize: size, batchCount: count })
        ).toBe(expectedMs(size, count));
      });
    }
  }

  it('nginxの上限で頭打ちになる', () => {
    expect(
      apiClient.imageGenerationMinTimeoutMs({ prompt: '猫', batchSize: 1000, batchCount: 1000 })
    ).toBe(nginxMs);
  });

  it('定数の宣言形式が変わって読めないときは黙って通さず失敗する', () => {
    expect(() => intConstant('int X = 1;', 'MISSING', 'dummy')).toThrow(/MISSING/);
  });
});
