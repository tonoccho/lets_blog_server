/** 生成画像ギャラリーのステップ(issue #942 / AT-16)。 */

import { Then, When } from '../support/gherkin';
import { w } from './common.steps';
import * as apiClient from '../../src/apiClient';

When('生成画像の一覧を取得する', async (world) => {
  const scope = w(world);
  (scope as { images?: unknown }).images = await apiClient.listGeneratedImages(
    scope.token,
    scope.actor,
    scope.project.id
  );
});

Then('生成画像の一覧が取得できる', (world) => {
  const images = (world as { images?: { id: number; prompt: string }[] }).images;
  if (!Array.isArray(images)) {
    throw new Error(`一覧が配列ではありません: ${JSON.stringify(images)}`);
  }
  for (const image of images) {
    if (typeof image.id !== 'number' || typeof image.prompt !== 'string') {
      throw new Error(`一覧の項目が想定の形ではありません: ${JSON.stringify(image)}`);
    }
  }
});

/**
 * batch sizeで指定した枚数を拡張が取りこぼさずに受け取ることの検証(issue #1104)。
 * サーバーは AiImageBatchResponse として全枚数を返すが、拡張がそこから何枚を取り出すかは
 * 拡張自身の apiClient を通さないと観測できない。
 */
When('batch size {int} で画像を生成する', async (world, batchSize) => {
  const scope = w(world);
  (scope as { generated?: unknown }).generated = await apiClient.generateImage(
    scope.token,
    scope.actor,
    scope.project.id,
    {
      prompt: 'a plain blue square, flat color',
      // 受け入れ基準は「枚数」であって画質ではない。生成時間を抑えるため最小サイズ・少ステップにする。
      width: 64,
      height: 64,
      steps: 4,
      batchSize: Number(batchSize),
    }
  );
});

Then('生成画像が {int} 枚返る', (world, expected) => {
  const generated = (world as { generated?: unknown }).generated;
  if (!Array.isArray(generated)) {
    throw new Error(`生成結果が配列ではありません: ${JSON.stringify(generated)}`);
  }
  if (generated.length !== Number(expected)) {
    throw new Error(`生成枚数が想定と異なります(期待 ${expected} / 実際 ${generated.length})`);
  }
  for (const image of generated as { fileName?: unknown; dataBase64?: unknown }[]) {
    if (typeof image.fileName !== 'string' || typeof image.dataBase64 !== 'string' || image.dataBase64 === '') {
      throw new Error(`生成結果の項目が想定の形ではありません: ${JSON.stringify(Object.keys(image))}`);
    }
  }
});
