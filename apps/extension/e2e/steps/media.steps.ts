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
