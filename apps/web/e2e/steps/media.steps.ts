import type { APIRequestContext, BrowserContext, Locator, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
  loginViaKeycloak,
} from '../support';
import { STUB_URLS, forceStubStatus, resetStub, stubRequestCount } from '../support/stubs';
import { magnifierOf, selectedCountText } from '../support/galleryCard';

/**
 * 画像ギャラリーのseed表示のステップ定義(issue #1101)。
 *
 * 画像そのものの生成は行わない。ComfyUIはGPU必須の任意サービスで受け入れテスト環境に
 * 常在せず、OpenAI画像スタブはseedの概念を持たないため、「どのseedで生成されたか」を
 * 生成経由で作り分けられない。ここで確かめたいのは**保存済みの画像の詳細画面が
 * seedをどう見せるか**なので、`POST /api/generated-images`(VSCode拡張が実際に使う
 * 保存経路)で行を作ってからギャラリーを開く。
 *
 * seedの実値決定そのもの(seed未指定でも非nullになる・バッチ内位置が0起点で振られる)は
 * サービスレベルのテストが担う(services/media の ImageGenerationServiceTest)。
 */

/** 1x1の透明PNG。中身は問わないので固定バイト列で足りる。 */
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

interface CreatedImage {
  id: number;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function createGeneratedImage(
  request: APIRequestContext,
  overrides: Record<string, unknown>
): Promise<number> {
  const token = await adminToken(request);
  const response = await request.post('/api/generated-images', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      prompt: 'e2e seed fixture',
      negativePrompt: 'blurry',
      steps: 20,
      cfgScale: 7.0,
      samplerName: 'euler',
      scheduler: 'normal',
      width: 512,
      height: 512,
      batchSize: 1,
      checkpoint: 'e2e.safetensors',
      mimeType: 'image/png',
      imageData: PNG_BASE64,
      ...overrides,
    },
  });
  expect(
    response.ok(),
    `生成画像の保存に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return ((await response.json()) as CreatedImage).id;
}

/** ギャラリーのシナリオが作った画像の「プロンプト → id」。一括削除(issue #1492)で複数枚を扱う。 */
function galleryImageIds(ctx: Record<string, unknown>): Record<string, number> {
  ctx.mediaGalleryImageIds ??= {};
  return ctx.mediaGalleryImageIds as Record<string, number>;
}

function selectedImageIds(ctx: Record<string, unknown>): number[] {
  ctx.mediaGallerySelectedIds ??= [];
  return ctx.mediaGallerySelectedIds as number[];
}

async function generatedImageFileStatus(
  request: APIRequestContext,
  headers: Record<string, string>,
  id: number
): Promise<number> {
  return (await request.get(`/api/generated-images/${id}/file`, { headers })).status();
}

/** 詳細ダイアログの、指定した項目名に対応する値。 */
function detailValue(page: Page, label: string) {
  return page.locator(`dt:text-is("${label}") + dd`);
}

Given(
  /^seed「(\d+)」・バッチ内位置「(\d+)」のComfyUI画像がギャラリーにある$/,
  async ({ ctx, request }, seed: string, batchIndex: string) => {
    ctx.mediaImageId = await createGeneratedImage(request, {
      provider: 'COMFYUI',
      seed: Number(seed),
      batchSize: 2,
      batchIndex: Number(batchIndex),
    });
  }
);

Given('seedを持たないChatGPT画像がギャラリーにある', async ({ ctx, request }) => {
  ctx.mediaImageId = await createGeneratedImage(request, {
    provider: 'CHATGPT',
    seed: null,
    batchIndex: null,
  });
});

/**
 * 虫眼鏡の `<button>`(`ImageGalleryGrid`、issue #1614)はクライアントコンポーネントで、SSRされた
 * 直後はまだハイドレーションが完了しておらず `onClick` が紐付いていない。実ブラウザの
 * ホストではヘッドレスCIよりハイドレーションが遅く、その間にクリックすると取りこぼされ、
 * 詳細ダイアログが開かないまま `toBeVisible` がタイムアウトする(issue #1284、#1283と同種)。
 * 見出しが現れるまでクリックを再試行する。
 */
When('画像ギャラリーでその画像の詳細を開く', async ({ ctx, page }) => {
  await page.goto('/image-gallery', { waitUntil: 'commit' });
  const thumbnail = page.locator(`img[src="/image-gallery/${ctx.mediaImageId}/file"]`);
  await expect(thumbnail).toBeVisible({ timeout: 30_000 });

  await expect(async () => {
    await magnifierOf(thumbnail).click();
    await expect(page.getByText('生成画像の詳細')).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });

  await expect(detailValue(page, 'prompt')).toBeVisible({ timeout: 15_000 });
});

// `(\d+)` はcucumber-expressionsの組み込み `int` 型と正規表現が一致するため、TSの型注釈に
// 関わらず実行時には数値(number)で渡ってくる(playwright-bdd/@cucumber/cucumber-expressions)。
// `toHaveText()` は string | RegExp しか受け付けないため、明示的に文字列化する。
Then(/^詳細にseed「(\d+)」が表示される$/, async ({ page }, seed: string) => {
  await expect(detailValue(page, 'seed')).toHaveText(String(seed));
});

Then(/^詳細にバッチ内位置「(\d+)」が表示される$/, async ({ page }, batchIndex: string) => {
  await expect(detailValue(page, 'batch index')).toHaveText(String(batchIndex));
});

Then('詳細にseedの値は表示されず、再現不可と分かる表示になる', async ({ page }) => {
  await expect(detailValue(page, 'seed')).toContainText('再現不可');
});

// issue #1647: 種別(アップロード / AI生成)の絞り込み。29件のうち最新と最初に作った画像だけをアップロード画像にし、
// 最初の1件は初回表示の24件に含まれない位置にある(続きのページにも条件が引き継がれることの確認)。
const SOURCE_FIXTURE_COUNT = 29;

function sourceProviderOf(index: number): string {
  if (index === 0 || index === SOURCE_FIXTURE_COUNT - 1) return 'UPLOAD';
  return index === SOURCE_FIXTURE_COUNT - 2 ? 'CHATGPT' : 'COMFYUI';
}

Given(
  /^ギャラリーに固定画像の生成画像を29件作成し、最新と最初に作成した1件だけをアップロード画像にする$/,
  async ({ ctx, request }) => {
    await createPagingFixtures(request, ctx, SOURCE_FIXTURE_COUNT, [], undefined, sourceProviderOf);
  }
);

Given(
  /^ギャラリーに固定画像の生成画像を29件作成し、最新・中間・最初に作成した3件にだけタグ「([^」]+)」を付け、最新と最初に作成した1件だけをアップロード画像にする$/,
  async ({ ctx, request }, tag: string) => {
    await createPagingFixtures(
      request,
      ctx,
      SOURCE_FIXTURE_COUNT,
      [0, 14, SOURCE_FIXTURE_COUNT - 1],
      tag,
      sourceProviderOf
    );
  }
);

function sourceChip(page: Page, name: string): Locator {
  return page.getByRole('group', { name: '種別で絞り込み' }).getByRole('button', { name, exact: true });
}

When(/^種別「(すべて|アップロード|AI生成)」で絞り込む$/, async ({ page }, name: string) => {
  await expect(galleryThumbnails(page).first()).toBeVisible({ timeout: 30_000 });
  const chip = sourceChip(page, name);
  // SSR 直後はハイドレーション前でクリックが失われるので、選択状態になるまで押し直す。
  await expect(async () => {
    await chip.click();
    await expect(chip).toHaveClass(/bg-neutral-900/, { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
});

/** 絞り込み結果が固定画像の集合 expected と一致するまで待つ(固定画像以外は共有環境の他の画像なので無視する)。 */
async function expectShownFixtures(page: Page, ctx: Record<string, unknown>, indexes: number[]): Promise<void> {
  const fixtures = ctx.mediaPagingIds as number[];
  const expected = indexes.map((i) => fixtures[i]).sort((a, b) => a - b);
  await expect
    .poll(
      async () => (await thumbnailIds(page)).filter((id) => fixtures.includes(id)).sort((a, b) => a - b),
      { timeout: 30_000 }
    )
    .toEqual(expected);
}

Then('一覧に表示される固定画像はアップロード画像の2件だけである', async ({ ctx, page }) => {
  await expectShownFixtures(page, ctx, [0, SOURCE_FIXTURE_COUNT - 1]);
});

Then('一覧に表示される固定画像は中間に作成した1件だけである', async ({ ctx, page }) => {
  await expectShownFixtures(page, ctx, [14]);
});

Then('一覧にアップロード画像とAI生成画像の両方の固定画像が表示される', async ({ ctx, page }) => {
  const fixtures = ctx.mediaPagingIds as number[];
  const newestUpload = fixtures[SOURCE_FIXTURE_COUNT - 1];
  const newestAi = fixtures[SOURCE_FIXTURE_COUNT - 2];
  await expect
    .poll(async () => {
      const shown = await thumbnailIds(page);
      return shown.includes(newestUpload) && shown.includes(newestAi);
    }, { timeout: 30_000 })
    .toBe(true);
});

When('AI生成の固定画像27件がすべて現れるまで一覧の末尾までスクロールする', async ({ ctx, page }) => {
  const fixtures = ctx.mediaPagingIds as number[];
  const aiFixtures = fixtures.slice(1, SOURCE_FIXTURE_COUNT - 1);
  await expect(galleryThumbnails(page).first()).toBeVisible({ timeout: 30_000 });
  await expect
    .poll(
      async () => {
        await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
        const shown = await thumbnailIds(page);
        return aiFixtures.every((id) => shown.includes(id));
      },
      { timeout: 60_000, intervals: [300] }
    )
    .toBe(true);
});

Then('一覧にアップロード画像の固定画像は1件も表示されていない', async ({ ctx, page }) => {
  const fixtures = ctx.mediaPagingIds as number[];
  const shown = await thumbnailIds(page);
  expect(shown).not.toContain(fixtures[0]);
  expect(shown).not.toContain(fixtures[SOURCE_FIXTURE_COUNT - 1]);
});

After({ tags: '@media' }, async ({ ctx, request }) => {
  const id = ctx.mediaImageId as number | undefined;
  if (id === undefined) {
    return;
  }
  const token = await adminToken(request);
  await request.delete(`/api/generated-images/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
});

/**
 * 画像生成のバッチ回数(issue #1102)のステップ定義。
 *
 * ここだけは実際に `POST /api/ai/image` を叩く。batch count は「1回の要求で何回繰り返すか」
 * であり、要求を実際に投げなければ確かめようがないため。画像生成AIには ChatGPT スタブ
 * (`infra/e2e-stubs/openai-image/server.js`)を使う。ComfyUI はGPU必須の任意サービスで
 * 受け入れテスト環境に常在しないため、ここから到達できない。
 *
 * その帰結として、**seed に関する受入基準はここでは確かめられない**
 * (ChatGPT の画像生成APIは seed を受け付けない)。リピートごとの seed の変化は
 * services/media の ImageGenerationServiceTest が担当する。
 *
 * `/api/ai/image` は gateway の upload-endpoint 枠(プロセス全体で1時間に10回)に属する。
 * シナリオを足すときは枠を意識すること(docs/ACCEPTANCE_TESTING.md §11)。
 */

interface ImageGenerationOutcome {
  status: number;
  body: string;
  images: { id: number }[];
  /** 要求前に存在した生成画像のID集合。「1枚も増えていない」の比較に使う。 */
  idsBefore: number[];
  /**
   * 要求前に ChatGPT 画像生成スタブが受け取っていた件数(issue #936、シナリオ3)。
   * 「ChatGPT の経路を通ったか」は生成された画像だけを見ても分からない
   * (どちらのプロバイダでも同じ形の画像が返る)ため、スタブの受信件数の増分で見る。
   * スタブが起動していない実行(`@stub` を付けないシナリオ)では null になる。
   */
  chatGptStubCallsBefore: number | null;
}

/**
 * ChatGPT 画像生成スタブの受信件数。スタブが起動していなければ null を返す。
 *
 * ここを例外にしないのは、この関数を通る {@link requestImageGeneration} が
 * `@stub` の付いていないシナリオ(`image-batch-count.feature`)からも使われるため。
 * スタブの起動を要求するのは `@stub` の Before(`steps/stubs.steps.ts`)の仕事であって、
 * この関数の仕事ではない。
 */
async function chatGptStubCalls(): Promise<number | null> {
  try {
    return await stubRequestCount('openai-image');
  } catch {
    return null;
  }
}

async function listGeneratedImageIds(request: APIRequestContext): Promise<number[]> {
  const token = await adminToken(request);
  const response = await request.get('/api/generated-images', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `生成画像一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return ((await response.json()) as { id: number }[]).map((image) => image.id);
}

async function requestImageGeneration(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  body: Record<string, unknown>
): Promise<void> {
  const token = await adminToken(request);
  const idsBefore = await listGeneratedImageIds(request);
  const chatGptStubCallsBefore = await chatGptStubCalls();
  const response = await request.post('/api/ai/image', {
    headers: { Authorization: `Bearer ${token}` },
    data: { prompt: 'e2e batch count', ...body },
    timeout: 180_000,
  });
  const text = await response.text();
  let images: { id: number }[] = [];
  if (response.ok()) {
    images = (JSON.parse(text) as { images: { id: number }[] }).images;
  }
  ctx.imageGeneration = {
    status: response.status(),
    body: text,
    images,
    idsBefore,
    chatGptStubCallsBefore,
  } satisfies ImageGenerationOutcome;
  ctx.mediaGeneratedIds = images.map((image) => image.id);
}

function outcome(ctx: Record<string, unknown>): ImageGenerationOutcome {
  const value = ctx.imageGeneration as ImageGenerationOutcome | undefined;
  expect(value, '画像生成の要求がまだ行われていません').toBeDefined();
  return value as ImageGenerationOutcome;
}

Given('画像生成にChatGPTを使うプロジェクトがある', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const created = await request.post('/api/projects', {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `E2E 1102 ${suffix}`, slug: `e2e-1102-${suffix}` },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const projectId = ((await created.json()) as { id: number }).id;
  const selected = await request.put(`/api/projects/${projectId}/ai-models/image/provider/selection`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { provider: 'CHATGPT' },
  });
  expect(
    selected.ok(),
    `画像生成AIの選択に失敗しました (status=${selected.status()}): ${await selected.text()}`
  ).toBe(true);
  // issue #1521: ChatGPTの画像生成はプロジェクトに設定したキーだけを使う(システム設定へは落ちない)。
  await setProjectChatGptApiKey(request, token, projectId, 'sk-at-1521-image-stub');
  ctx.mediaProjectId = projectId;
});

Given('ChatGPTを選んだがAPIキーを設定していないプロジェクトがある', async ({ ctx, request }) => {
  await createProjectWithImageProvider(request, ctx, 'CHATGPT', '1521-nokey');
});

async function setProjectChatGptApiKey(
  request: APIRequestContext,
  token: string,
  projectId: number,
  apiKey: string
): Promise<void> {
  const response = await request.put(`/api/projects/${projectId}/api-keys/openai-api-key`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { apiKey },
  });
  expect(
    response.ok(),
    `プロジェクトのChatGPT APIキー設定に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

When(
  /^そのプロジェクトで1回に「(\d+)」枚を「(\d+)」回繰り返す画像生成を要求する$/,
  async ({ ctx, request }, batchSize: string, batchCount: string) => {
    await requestImageGeneration(request, ctx, {
      projectId: ctx.mediaProjectId,
      batchSize: Number(batchSize),
      batchCount: Number(batchCount),
    });
  }
);

When(
  /^そのプロジェクトで1回に「(\d+)」枚の画像生成を繰り返し回数を指定せずに要求する$/,
  async ({ ctx, request }, batchSize: string) => {
    await requestImageGeneration(request, ctx, {
      projectId: ctx.mediaProjectId,
      batchSize: Number(batchSize),
    });
  }
);

When(
  /^そのプロジェクトで1回に「(\d+)」枚の画像生成を要求する$/,
  async ({ ctx, request }, batchSize: string) => {
    await requestImageGeneration(request, ctx, {
      projectId: ctx.mediaProjectId,
      batchSize: Number(batchSize),
    });
  }
);

When(/^1回に「(\d+)」枚の画像生成を要求する$/, async ({ ctx, request }, batchSize: string) => {
  await requestImageGeneration(request, ctx, { batchSize: Number(batchSize) });
});

When(
  /^1回に「(\d+)」枚を「(\d+)」回繰り返す画像生成を要求する$/,
  async ({ ctx, request }, batchSize: string, batchCount: string) => {
    await requestImageGeneration(request, ctx, {
      batchSize: Number(batchSize),
      batchCount: Number(batchCount),
    });
  }
);

Then(/^生成された画像が「(\d+)」枚返る$/, async ({ ctx }, expected: string) => {
  const result = outcome(ctx);
  expect(
    result.status,
    `画像生成に失敗しました (status=${result.status}): ${result.body}`
  ).toBe(200);
  expect(result.images).toHaveLength(Number(expected));
});

Then('返った画像がすべて生成画像の一覧に現れる', async ({ ctx, request }) => {
  const result = outcome(ctx);
  const ids = await listGeneratedImageIds(request);
  for (const image of result.images) {
    expect(ids, `生成画像 ${image.id} が一覧に現れていません`).toContain(image.id);
  }
});

Then(/^画像生成の要求が「(\d+)」で拒否される$/, async ({ ctx }, status: string) => {
  const result = outcome(ctx);
  expect(result.status, `応答本文: ${result.body}`).toBe(Number(status));
});

Then('生成画像は1枚も増えていない', async ({ ctx, request }) => {
  const result = outcome(ctx);
  const ids = await listGeneratedImageIds(request);
  expect(ids.filter((id) => !result.idsBefore.includes(id))).toEqual([]);
});

Then(
  /^拒否の理由に画像生成AIの名前「([^」]+)」と上限「(\d+)」が示される$/,
  async ({ ctx }, provider: string, limit: string) => {
    const result = outcome(ctx);
    expect(result.body).toContain(provider);
    expect(result.body).toContain(String(limit));
  }
);

After({ tags: '@media' }, async ({ ctx, request }) => {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  for (const id of (ctx.mediaGeneratedIds as number[] | undefined) ?? []) {
    await request.delete(`/api/generated-images/${id}`, { headers });
  }
  const projectId = ctx.mediaProjectId as number | undefined;
  if (projectId !== undefined) {
    await request.delete(`/api/projects/${projectId}`, { headers });
  }
});

/**
 * アセット画像生成フォームのbatch size / batch count(issue #1103)のステップ定義。
 *
 * ここは #1102 のステップと違い、APIを直接叩かず**画面から**操作する。確かめたいのが
 * 「フォームがどの値を出し、どの値を送り、返ってきた枚数をどう見せるか」だからである。
 * 画像生成AIには ChatGPT スタブを使う(ComfyUI はGPU必須の任意サービスで受け入れテスト環境に
 * 常在しない)。スタブは1回に10枚までしか作れないため、16×16 のシナリオは**サーバー側で
 * 拒否される**のが正しい結末で、それでもフォームが要求を送ったことがこのシナリオの主張になる。
 *
 * 生成された画像の後始末は上の `@media` の After が ctx.mediaProjectId ごと行う
 * (画面から作った画像はプロジェクトに紐づくため、プロジェクト削除で一緒に消える)。
 */

/** アセット画像生成パネルの、ラベル文字列で特定する数値入力。 */
function panelNumberInput(page: Page, label: string) {
  return page.getByLabel(label);
}

When('そのプロジェクトの管理画面でアセット画像生成パネルを開く', async ({ ctx, page }) => {
  // アセット画像生成パネルは「AI・アセット」タブの中にしか描画されない(issue #1127)。
  // タブを開かずに直接ボタンを探すと、既定タブ「概要」にはボタンが存在せず失敗する。
  await openProjectTab(page, ctx.mediaProjectId as number, 'AI・アセット');
  await page.locator('button:has-text("アセット画像生成")').click();
  await expect(page.getByRole('heading', { name: 'アセット画像生成' })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByPlaceholder('生成したい画像の説明')).toBeVisible({ timeout: 15_000 });
});

// `(\d+)` はcucumber-expressionsの組み込み `int` 型と正規表現が一致するため、TSの型注釈に
// 関わらず実行時には数値(number)で渡ってくる(playwright-bdd/@cucumber/cucumber-expressions、
// a96990e1/#1284と同種)。`toHaveAttribute()` は string | RegExp しか受け付けないため、
// 明示的に文字列化する(issue #1315)。
Then(/^batch sizeの入力があり、上限は「(\d+)」である$/, async ({ page }, max: string) => {
  const input = panelNumberInput(page, `batch size(最大${max})`);
  await expect(input).toBeVisible();
  await expect(input).toHaveAttribute('max', String(max));
});

Then(/^batch countの入力があり、上限は「(\d+)」である$/, async ({ page }, max: string) => {
  const input = panelNumberInput(page, `batch count(最大${max})`);
  await expect(input).toBeVisible();
  await expect(input).toHaveAttribute('max', String(max));
});

Then('合計枚数の目安と、枚数によっては長時間かかる旨が表示される', async ({ page }) => {
  await expect(page.getByText(/この設定で合計\d+枚/)).toBeVisible();
  await expect(page.getByText(/非常に長時間かかります/)).toBeVisible();
});

When(
  /^batch sizeに「(\d+)」、batch countに「(\d+)」を入力して生成を要求する$/,
  async ({ ctx, page }, batchSize: string, batchCount: string) => {
    // 上と同じ理由(#1315)で batchSize/batchCount は実行時には number。
    // `Locator.fill()` は string しか受け付けないため文字列化する。
    // 生成はジョブとして受理され、結果は処理キューに現れる(issue #1408)。ジョブを後から特定できるよう、
    // プロンプトに要求ごとの印を入れる(並列に走る他のシナリオのジョブと取り違えない)。
    const prompt = `e2e 1408 asset image ${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    ctx.assetJobPrompt = prompt;
    // パネルはクリックで開いたあとに描画されるため既にハイドレーション済みだが、入力値が
    // 反映されたことを確かめてから押す(押した時点の state を送るので、空振りは要求が空になる)。
    const promptInput = page.getByPlaceholder('生成したい画像の説明');
    await promptInput.fill(prompt);
    await expect(promptInput).toHaveValue(prompt);
    await panelNumberInput(page, 'batch size(最大16)').fill(String(batchSize));
    await panelNumberInput(page, 'batch count(最大16)').fill(String(batchCount));
    const generate = page.getByRole('button', { name: '生成', exact: true });
    await expect(generate).toBeEnabled();
    await generate.click();
  }
);

/** 処理キューの項目(ジョブIDで特定する)。 */
function queueItem(page: Page, jobId: number) {
  return page.locator(`[data-testid="info-rail-queue-item"][data-job-id="${jobId}"]`);
}

/**
 * このシナリオのパネルが要求した画像生成ジョブを、管理者の一覧からプロンプトの印で特定する。
 * 受理後に作られるので、現れるまで短く再試行する。
 */
async function findAssetJob(
  request: APIRequestContext,
  prompt: string
): Promise<{ id: number; resultPayload: string | null; status: string }> {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const list = await request.get('/api/generation-jobs', { headers });
    expect(list.ok(), `ジョブ一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
    const jobs = (await list.json()) as Array<{ id: number; type: string }>;
    for (const job of jobs.filter((j) => j.type === 'image_generation')) {
      const detail = await request.get(`/api/generation-jobs/${job.id}`, { headers });
      if (!detail.ok()) continue;
      const body = (await detail.json()) as {
        id: number;
        status: string;
        requestPayload: string | null;
        resultPayload: string | null;
      };
      if (body.requestPayload?.includes(prompt)) {
        return { id: body.id, resultPayload: body.resultPayload, status: body.status };
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`印(${prompt})を持つ画像生成ジョブが見つかりません`);
}

Then('生成の要求を受け付けた旨が示され、生成ボタンは押せる状態のままである', async ({ page }) => {
  await expect(page.getByText(/生成を要求しました。処理キューに追加されました/)).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByRole('button', { name: '生成', exact: true })).toBeEnabled();
  // 完了を待たないので、結果のグリッドはまだ(この画面では)出ない。
  await expect(page.getByTestId('generated-image-grid')).toHaveCount(0);
});

Then('処理キューにその画像生成のジョブが現れる', async ({ ctx, page, request }) => {
  const job = await findAssetJob(request, ctx.assetJobPrompt as string);
  ctx.assetJobId = job.id;
  await expect(queueItem(page, job.id)).toBeVisible({ timeout: 30_000 });
  await expect(queueItem(page, job.id)).toContainText('画像生成');
});

When('ダッシュボードへ移動してから、そのプロジェクトの管理画面へ戻る', async ({ ctx, page }) => {
  // 「ジョブの実行中に」移動するのが条件なので、まず要求が受理された(ジョブが作られた)ことを待つ。
  // 生成ボタンを押した直後に移動すると、送信中の Server Action がブラウザに中断され、ジョブが
  // 作られる前に要求ごと失われる(受理前の離脱であって、このシナリオが確かめる「受理後の離脱」ではない)。
  await expect(page.getByText(/生成を要求しました。処理キューに追加されました/)).toBeVisible({
    timeout: 30_000,
  });
  await page.goto('/', { waitUntil: 'commit' });
  await page.goto(`/projects/${ctx.mediaProjectId}`, { waitUntil: 'commit' });
});

Given('そのプロジェクトに、別の要求で生成された画像が1枚ある', async ({ ctx, request }) => {
  ctx.assetOtherImageId = await createGeneratedImage(request, {
    prompt: 'e2e 1408 another request',
    provider: 'CHATGPT',
    projectId: ctx.mediaProjectId,
  });
});

When('処理キューのその画像生成のジョブが完了するまで待つ', async ({ ctx, page, request }) => {
  const job = await findAssetJob(request, ctx.assetJobPrompt as string);
  ctx.assetJobId = job.id;
  await expect(queueItem(page, job.id)).toContainText('完了', { timeout: 180_000 });
});

When('処理キューのその画像生成のジョブの「結果を見る」を押す', async ({ ctx, page }) => {
  await queueItem(page, ctx.assetJobId as number)
    .getByRole('link', { name: '結果を見る' })
    .click();
});

Then('生成結果に別の要求で生成された画像は含まれない', async ({ ctx, page, request }) => {
  const job = await findAssetJob(request, ctx.assetJobPrompt as string);
  const expected = (JSON.parse(job.resultPayload ?? '{}') as { imageIds?: number[] }).imageIds ?? [];
  expect(expected.length, `ジョブの結果に画像がありません: ${job.resultPayload}`).toBeGreaterThan(0);
  const srcs = await page
    .getByTestId('generated-image-grid')
    .locator('img')
    .evaluateAll((imgs) => imgs.map((img) => (img as HTMLImageElement).getAttribute('src') ?? ''));
  const shown = srcs.map((src) => Number(/\/image-gallery\/(\d+)\/file/.exec(src)?.[1])).sort((x, y) => x - y);
  expect(shown).toEqual([...expected].sort((x, y) => x - y));
  expect(shown).not.toContain(ctx.assetOtherImageId as number);
});

When('batch sizeの入力を全消去する', async ({ page }) => {
  await panelNumberInput(page, 'batch size(最大16)').fill('');
});

Then('batch sizeの入力は空欄のままで「0」にならない', async ({ page }) => {
  await expect(panelNumberInput(page, 'batch size(最大16)')).toHaveValue('');
});

Then(/^生成結果に画像が「(\d+)」枚並ぶ$/, async ({ page }, expected: string) => {
  const grid = page.getByTestId('generated-image-grid');
  await expect(grid).toBeVisible({ timeout: 180_000 });
  await expect(grid.locator('img')).toHaveCount(Number(expected), { timeout: 180_000 });
});

Then('生成結果の1枚を選んでアセットとして追加できる', async ({ page }) => {
  await page.getByTestId('generated-image-grid').locator('img').first().click();
  await page.getByRole('button', { name: 'アセットとして追加(全環境へアップロード)' }).click();
  await expect(page.getByText(/環境へアップロードしました。|環境でアップロードに失敗しました。/)).toBeVisible({
    timeout: 120_000,
  });
});

Then('生成ボタンは押せる状態のままで、サーバーからの応答が表示される', async ({ page }) => {
  // フォームが要求を止めていれば、サーバーの応答は出ずボタンも押せないままになる。
  // ChatGPT は1回に10枚までなので、batch size 16 はジョブを作る前の受理検証(400)で断られ、
  // その理由(画像生成AI名を含む)がパネルに出る。非同期経路でも、枚数の拒否だけは受理前に
  // 同期で返る(失敗がジョブの中で起きるのは生成が始まってから)。
  await expect(page.getByText(/CHATGPT/)).toBeVisible({ timeout: 180_000 });
  await expect(page.getByRole('button', { name: '生成', exact: true })).toBeEnabled();
});

/**
 * ダークモードでの可読性(issue #1107)。
 *
 * アセット画像生成パネルのサブフォームは背景色を持つのに中の見出しが文字色を持たず、
 * body 由来の色を継承していた。ライトでは偶然読めるがダークでは背景とほぼ同色になる。
 * クラス名の検査は jest 側で足りるが、「実際に読める色になったか」は、カスケードを
 * ブラウザに解決させて初めて分かる。ここでは getComputedStyle が返す実効色から
 * WCAG 2.1 の相対輝度とコントラスト比を計算する。
 */

/** ThemeSwitcher が読む localStorage の値を、遷移前に仕込む。 */
Given('テーマをダークにする', async ({ page }) => {
  await page.addInitScript(() => {
    window.localStorage.setItem('theme', 'dark');
  });
});

Then(
  /^見出し「(.+)」の文字色と背景色のコントラスト比が「([\d.]+)」以上である$/,
  async ({ page }, headingText: string, minimum: string) => {
    const heading = page.getByRole('heading', { name: headingText });
    await expect(heading).toBeVisible({ timeout: 15_000 });

    const measured = await heading.evaluate((el) => {
      /** `rgb(r, g, b)` / `rgba(r, g, b, a)` を数値へ。解釈できなければ null。 */
      const parse = (value: string): [number, number, number, number] | null => {
        const m = value.match(/rgba?\(([^)]+)\)/);
        if (!m) return null;
        const parts = m[1].split(/[\s,/]+/).filter(Boolean).map(Number);
        if (parts.length < 3 || parts.some(Number.isNaN)) return null;
        return [parts[0], parts[1], parts[2], parts.length > 3 ? parts[3] : 1];
      };

      // 最近傍の「透明でない背景色」を祖先方向へ探す。どの祖先も透明なら白地とみなす。
      let node: Element | null = el;
      let background: [number, number, number, number] = [255, 255, 255, 1];
      while (node) {
        const parsed = parse(getComputedStyle(node).backgroundColor);
        if (parsed && parsed[3] > 0) {
          background = parsed;
          break;
        }
        node = node.parentElement;
      }
      const foreground = parse(getComputedStyle(el).color) ?? [0, 0, 0, 1];

      // WCAG 2.1 の相対輝度。
      const luminance = ([r, g, b]: number[]) => {
        const channel = (c: number) => {
          const s = c / 255;
          return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
        };
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
      };
      const lf = luminance(foreground);
      const lb = luminance(background);
      const ratio = (Math.max(lf, lb) + 0.05) / (Math.min(lf, lb) + 0.05);
      return {
        ratio,
        foreground: `rgb(${foreground.slice(0, 3).join(', ')})`,
        background: `rgb(${background.slice(0, 3).join(', ')})`,
      };
    });

    expect(
      measured.ratio,
      `見出し「${headingText}」のコントラスト比が不足しています: ` +
        `文字色 ${measured.foreground} / 背景色 ${measured.background} = ${measured.ratio.toFixed(2)}:1`
    ).toBeGreaterThanOrEqual(Number(minimum));
  }
);

/**
 * ここから下は issue #936(AT-10)。画像生成・ギャラリー・画像設定・ComfyUIチェックポイントの
 * 受け入れシナリオを支えるステップ定義。メディアGCだけは前提(WordPressサイトの構築)と
 * 後片付けの形が大きく違うため `mediaGarbageCollection.steps.ts` に分けてある。
 *
 * 画面から確かめるものとAPIから確かめるものが混在する。分け方の理由は各 `.feature` の
 * 冒頭に書いてある(要約: 画面のふるまいが受け入れ基準なら画面から、サーバーの判断が
 * 受け入れ基準ならAPIから)。
 */

/** 画像ギャラリーのURL。 */
const IMAGE_GALLERY_PATH = '/image-gallery';

interface GeneratedImageDetail {
  id: number;
  prompt: string;
  width: number;
  height: number;
  checkpoint: string | null;
  provider: string;
  tags: string[];
}

/** プロジェクトを作り、画像生成AIを選ぶ。フィクスチャは `@media` の After が片付ける。 */
async function createProjectWithImageProvider(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  provider: 'COMFYUI' | 'CHATGPT',
  slugPrefix: string
): Promise<number> {
  const token = await adminToken(request);
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const created = await request.post('/api/projects', {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `E2E ${slugPrefix} ${suffix}`, slug: `e2e-${slugPrefix}-${suffix}` },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const projectId = ((await created.json()) as { id: number }).id;
  const selected = await request.put(`/api/projects/${projectId}/ai-models/image/provider/selection`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { provider },
  });
  expect(
    selected.ok(),
    `画像生成AIの選択に失敗しました (status=${selected.status()}): ${await selected.text()}`
  ).toBe(true);
  ctx.mediaProjectId = projectId;
  return projectId;
}

/** 生成画像の詳細を取得する。 */
async function fetchGeneratedImageDetail(
  request: APIRequestContext,
  id: number
): Promise<GeneratedImageDetail> {
  const token = await adminToken(request);
  const response = await request.get(`/api/generated-images/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `生成画像の詳細取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as GeneratedImageDetail;
}

/** 直前の要求で生成された画像のうち1枚目。詳細を確かめるステップが使う。 */
function firstGeneratedImageId(ctx: Record<string, unknown>): number {
  const result = outcome(ctx);
  expect(
    result.status,
    `画像生成に失敗しました (status=${result.status}): ${result.body}`
  ).toBe(200);
  expect(result.images.length, '生成された画像がありません').toBeGreaterThan(0);
  return result.images[0].id;
}

/**
 * タブ切り替え後にしか現れない要素(#1283 と同型)。`Tabs`(`src/components/Tabs.tsx`)は
 * クライアントコンポーネントで、タブボタン自体はサーバーレンダリングされて先に
 * 見えているため、ハイドレーション完了前にクリックすると `onClick` がまだ
 * 紐付いておらず取りこぼされる。実ブラウザのホストではヘッドレスCIよりハイドレーション
 * が遅く、この取りこぼしが表面化しやすい。
 */
const PROJECT_TAB_MARKERS: Record<string, (page: Page) => Locator> = {
  'AI・アセット': (page) => page.locator('input[name="defaultGeneratedImageWidth"]'),
};

/**
 * プロジェクト詳細の指定タブを開く。パネルはタブの中にあるため、これを通らないと見えない。
 *
 * クリックそのものは成功しても、ハイドレーション前だとハンドラが付いておらず何も
 * 起きないことがある(#1283)。そのタブでしか現れないマーカーが見えるまでクリックを
 * 再試行する。
 */
async function openProjectTab(page: Page, projectId: number, tabLabel: string): Promise<void> {
  await page.goto(`/projects/${projectId}`, { waitUntil: 'commit' });
  const tab = page.getByRole('button', { name: tabLabel, exact: true });
  await expect(tab).toBeVisible({ timeout: 30_000 });

  const markerFactory = PROJECT_TAB_MARKERS[tabLabel];
  if (!markerFactory) {
    throw new Error(`openProjectTab: 未知のタブです(マーカー未登録): ${tabLabel}`);
  }
  const marker = markerFactory(page);

  await expect(async () => {
    await tab.click();
    await expect(marker).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

// ---- 画像生成(プロジェクトの準備) ----

Given('画像生成にComfyUIを使うプロジェクトがある', async ({ ctx, request }) => {
  await createProjectWithImageProvider(request, ctx, 'COMFYUI', '936-comfyui');
});

When(
  /^そのプロジェクトで「([^」]+)」の画像生成を要求する$/,
  async ({ ctx, request }, prompt: string) => {
    await requestImageGeneration(request, ctx, { projectId: ctx.mediaProjectId, prompt, batchSize: 1 });
  }
);

Then('ChatGPTの画像生成が呼ばれている', async ({ ctx }) => {
  const result = outcome(ctx);
  expect(
    result.chatGptStubCallsBefore,
    'ChatGPT画像生成スタブの受信件数を取得できませんでした(スタブが起動していません)'
  ).not.toBeNull();
  const after = await stubRequestCount('openai-image');
  expect(
    after,
    `ChatGPTの画像生成が呼ばれていません(要求前 ${result.chatGptStubCallsBefore} 件 / 要求後 ${after} 件)`
  ).toBeGreaterThan(result.chatGptStubCallsBefore as number);
});

Then('ChatGPTの画像生成は呼ばれていない', async ({ ctx }) => {
  const result = outcome(ctx);
  expect(
    result.chatGptStubCallsBefore,
    'ChatGPT画像生成スタブの受信件数を取得できませんでした(スタブが起動していません)'
  ).not.toBeNull();
  expect(await stubRequestCount('openai-image')).toBe(result.chatGptStubCallsBefore);
});

Then('拒否の理由にこのプロジェクトでAPIキーを設定するよう示される', async ({ ctx }) => {
  const result = outcome(ctx);
  expect(result.body).toContain('このプロジェクト');
  expect(result.body).toContain('APIキー');
});

Then(
  /^生成された画像の詳細の画像生成AIは「([A-Z]+)」である$/,
  async ({ ctx, request }, provider: string) => {
    const detail = await fetchGeneratedImageDetail(request, firstGeneratedImageId(ctx));
    expect(detail.provider).toBe(provider);
  }
);

/**
 * 生成画像のタグ提案(issue #281)がJSONとして解釈できているかを検証する(issue #1077)。
 *
 * `ImageGenerationService#suggestImageTagsJson` はLLMの応答をJSONとして解釈できないと
 * 例外を握りつぶし、タグ無し(null)で保存を続行する(補助機能のため画像生成自体は
 * 失敗させない設計)。したがって「タグが1件以上ある」ことだけが、LLMスタブが
 * このプロンプトへJSON形式で応答できているかの外部から観測できる唯一の手がかりになる。
 */
Then('生成された画像の詳細に1件以上のタグが提案されている', async ({ ctx, request }) => {
  const detail = await fetchGeneratedImageDetail(request, firstGeneratedImageId(ctx));
  expect(
    detail.tags,
    '生成画像にタグが1件も提案されていません(LLMスタブがJSON形式で応答できていない疑いがあります)'
  ).not.toHaveLength(0);
});

Given(
  /^ChatGPTの画像生成が次の1回だけ「(\d+)」で失敗するようにする$/,
  async ({ ctx }, status: string) => {
    await forceStubStatus('openai-image', Number(status), 1);
    ctx.mediaForcedStub = 'openai-image';
  }
);

/**
 * 「壊れた画像レコードが残らない」を**そのプロジェクトの中で**確かめる。
 *
 * 既存の「生成画像は1枚も増えていない」は全プロジェクトの一覧を見るため、並列に走る
 * 他のシナリオが作った画像を拾って落ちる(実測でそうなった)。失敗した生成が
 * 行を残さないことはプロジェクト単位で言えれば足りる。
 */
Then('そのプロジェクトの生成画像は1枚も残っていない', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const response = await request.get(`/api/generated-images?projectId=${ctx.mediaProjectId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `生成画像一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  expect(await response.json()).toEqual([]);
});

Then(
  /^拒否の理由に失敗した画像生成AIと状態コード「(\d+)」が示される$/,
  async ({ ctx }, status: string) => {
    const result = outcome(ctx);
    expect(result.body).toContain('ChatGPT');
    // playwright-bdd は数字だけのキャプチャを number として渡すため、明示的に文字列へ戻す。
    expect(result.body).toContain(String(status));
  }
);

/**
 * 仕込んだエラー注入が消費されずに残っていたときだけ解除する。
 *
 * **無条件に `resetStub()` を呼んではいけない。** リセットはスタブの受信件数を0へ戻すので、
 * 並列に走っている「ChatGPTの画像生成が呼ばれている」(受信件数の増分を見るシナリオ)を
 * 巻き添えで落とす。実測でそうなった。注入は `count: 1` なので通常はシナリオ内の
 * 1回の生成要求で消費され、ここで解除する必要は無い。残るのは、要求へ届く前に
 * シナリオが落ちた場合だけである。
 */
After({ tags: '@media' }, async ({ ctx }) => {
  if (ctx.mediaForcedStub !== 'openai-image') {
    return;
  }
  const response = await fetch(`${STUB_URLS['openai-image']}/__control/state`);
  const state = (await response.json()) as { forced: unknown };
  if (state.forced) {
    await resetStub('openai-image');
  }
});

// ---- ギャラリー(image-gallery.feature) ----

Given(
  /^プロンプト「([^」]+)」の生成画像がギャラリーにある$/,
  async ({ ctx, request }, prompt: string) => {
    ctx.mediaImageId = await createGeneratedImage(request, { prompt, provider: 'COMFYUI', seed: 936_000 });
    ctx.mediaImagePrompt = prompt;
    // 一括削除(issue #1492)は複数枚を扱う。プロンプトから id を引けるようにし、After が全部片付ける。
    galleryImageIds(ctx)[prompt] = ctx.mediaImageId as number;
  }
);

When('画像ギャラリーを開く', async ({ page }) => {
  await page.goto(IMAGE_GALLERY_PATH, { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: '画像ギャラリー' })).toBeVisible({ timeout: 30_000 });
});

Then(
  /^ギャラリーにプロンプト「([^」]+)」の画像が表示される$/,
  async ({ page }, prompt: string) => {
    await expect(page.locator(`img[alt="${prompt}"]`)).toBeVisible({ timeout: 30_000 });
  }
);

Then(
  /^ギャラリーにプロンプト「([^」]+)」の画像は表示されない$/,
  async ({ page }, prompt: string) => {
    await expect(page.locator(`img[alt="${prompt}"]`)).toHaveCount(0, { timeout: 30_000 });
  }
);

Then(
  /^詳細に「([^」]+)」として「([^」]+)」が表示される$/,
  async ({ page }, label: string, value: string) => {
    await expect(detailValue(page, label)).toHaveText(value);
  }
);

When(/^タグ「([^」]+)」を追加する$/, async ({ page }, tag: string) => {
  await page.getByPlaceholder('タグを追加').fill(tag);
  await page.getByRole('button', { name: '追加', exact: true }).click();
});

Then(/^詳細のタグ一覧に「([^」]+)」が表示される$/, async ({ page }, tag: string) => {
  await expect(page.getByRole('button', { name: `タグ「${tag}」を削除` })).toBeVisible({ timeout: 30_000 });
});

Then(
  /^ギャラリーを開き直すとタグ「([^」]+)」で絞り込める$/,
  async ({ ctx, page }, tag: string) => {
    await page.goto(IMAGE_GALLERY_PATH, { waitUntil: 'commit' });
    const filter = page.getByRole('button', { name: tag, exact: true });
    await expect(filter).toBeVisible({ timeout: 30_000 });
    await filter.click();
    await expect(page.locator(`img[alt="${ctx.mediaImagePrompt as string}"]`)).toBeVisible();
  }
);

When('詳細から画像を削除する', async ({ page }) => {
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '削除', exact: true }).click();
  await expect(page.getByText('生成画像の詳細')).toHaveCount(0, { timeout: 30_000 });
});

/**
 * ログイン必須の生成画像配信のCache-Control(issue #1064)。
 *
 * `page.goto()` で直接ナビゲーションすることで、実際のブラウザのHTTPキャッシュを経由させる。
 * `request` フィクスチャ(APIRequestContext)は`page`とキャッシュを共有しないため、
 * 「ログアウト後にブラウザキャッシュから返ってしまわないか」は`page`でしか確かめられない
 * (`context.clearCookies()`はHTTPキャッシュを消さないため、同一の`page`/`BrowserContext`を
 * ログイン→閲覧→ログアウト→再アクセスまで使い続けることが要になる)。
 */
When('その画像のファイルへ直接アクセスする', async ({ ctx, page }) => {
  ctx.mediaFileResponse = await page.goto(`/image-gallery/${ctx.mediaImageId}/file`, {
    waitUntil: 'commit',
  });
});

/**
 * ログアウトをUIのクリックを経由せず、next-auth/reactのsignOut()が内部で行うのと同じ
 * HTTP呼び出し(csrfToken取得 → `/api/auth/signout`へPOST)を`page.request`で直接行う。
 *
 * `page.request`は`page`と同じ`BrowserContext`のCookieを共有するため、ページを操作せずに
 * セッションだけを終了できる。issue #1236(サーバー/ブラウザのタイムゾーン差によるハイドレー
 * ション不一致でクリックが失われることがある、本Issueとは無関係の既知の不具合)を避けつつ、
 * 実際にアプリが使うのと同じサインアウト経路を通すための選択(本物のUIクリックの代用であり、
 * セッションCookieを直接消すような近道ではない)。
 */
When('そのページのセッションをログアウトAPI経由で終了する', async ({ page }) => {
  const csrfResponse = await page.request.get('/api/auth/csrf');
  expect(csrfResponse.ok(), `CSRFトークンの取得に失敗しました (status=${csrfResponse.status()})`).toBe(true);
  const { csrfToken } = (await csrfResponse.json()) as { csrfToken: string };
  const signOutResponse = await page.request.post('/api/auth/signout', {
    form: { csrfToken },
  });
  expect(
    signOutResponse.ok(),
    `ログアウトAPIの呼び出しに失敗しました (status=${signOutResponse.status()})`
  ).toBe(true);
});

/**
 * UIのモーダル操作(issue #1236の影響を受けうる)を経由せず、APIで直接削除する。
 *
 * `ctx.mediaImageId`はこの後の「その画像のファイルへ直接アクセスする」でも使うため保持し、
 * @mediaのAfterが重ねて消そうとする分は削除済みIDへの2度目のDELETE(無視される)に留める。
 */
When('その画像をAPI経由で削除する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const response = await request.delete(`/api/generated-images/${ctx.mediaImageId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok(), `画像の削除に失敗しました (status=${response.status()})`).toBe(true);
});

Then('画像の取得は成功する', async ({ ctx }) => {
  const response = ctx.mediaFileResponse as import('@playwright/test').Response | null;
  expect(response, 'ファイルへのアクセス結果が記録されていません').not.toBeNull();
  expect(response?.status()).toBe(200);
});

/**
 * `apps/web/src/proxy.ts`はセッション(NextAuthのトークン)を持たないアクセスを
 * `/image-gallery/**`を含む保護パス全般で`/login`へリダイレクトする(このハンドラ自身の
 * `getSession()`チェックより手前で働く)。そのため未ログイン状態での直接アクセスは
 * (ブラウザキャッシュを経由しない限り)401ではなく`/login`へのリダイレクト=最終的に
 * 200で`/login`のHTMLが返る形になる。ここで確かめたいのは画像そのものが返らないことなので、
 * 最終URLとContent-Typeの両方を見る。
 */
Then('ログイン画面へ転送され、画像は返らない', async ({ ctx, page }) => {
  const response = ctx.mediaFileResponse as import('@playwright/test').Response | null;
  expect(response, 'ファイルへのアクセス結果が記録されていません').not.toBeNull();
  expect(page.url(), 'ログアウト後もログイン画面へ転送されていない').toContain('/login');
  const contentType = response?.headers()['content-type'] ?? '';
  expect(
    contentType,
    `ログアウト後も画像本体が返っている疑いがある(issue #1064): content-type=${contentType}`
  ).not.toContain('image/');
});

Then('画像の取得は成功しない', async ({ ctx }) => {
  const response = ctx.mediaFileResponse as import('@playwright/test').Response | null;
  expect(response, 'ファイルへのアクセス結果が記録されていません').not.toBeNull();
  const contentType = response?.headers()['content-type'] ?? '';
  expect(
    contentType,
    `削除したはずの画像本体が返っている疑いがある(issue #1064): content-type=${contentType}`
  ).not.toContain('image/');
});

Then('応答のCache-Controlに「public」は含まれない', async ({ ctx }) => {
  const response = ctx.mediaFileResponse as import('@playwright/test').Response | null;
  const cacheControl = response?.headers()['cache-control'] ?? '';
  expect(
    cacheControl,
    `Cache-Controlに"public"が含まれ、共有キャッシュ経由での取得を許してしまっている: ${cacheControl}`
  ).not.toMatch(/(^|[,\s])public(\s|,|$)/);
});

Then('応答のCache-Controlは「no-store」を含む', async ({ ctx }) => {
  const response = ctx.mediaFileResponse as import('@playwright/test').Response | null;
  const cacheControl = response?.headers()['cache-control'] ?? '';
  expect(cacheControl, `Cache-Control: ${cacheControl}`).toContain('no-store');
});

Then('その画像のファイルはもう取得できない', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const response = await request.get(`/api/generated-images/${ctx.mediaImageId}/file`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.status(),
    `削除したはずの画像のファイルが取得できてしまいました (status=${response.status()})`
  ).toBe(404);
  // 削除済みなので @media の After が重ねて消さないようにする。
  ctx.mediaImageId = undefined;
});

// ---- ギャラリーの複数選択と一括削除(issue #1492) ----

const BULK_DELETE_BUTTON = /^選択した\d+件を削除$/;

When(
  /^ギャラリーでプロンプト「([^」]+)」の画像を選択する$/,
  async ({ ctx, page }, prompt: string) => {
    const checkbox = page.getByLabel(`${prompt}を選択`);
    await expect(checkbox).toBeVisible({ timeout: 30_000 });
    // ハイドレーション完了前のクリックはネイティブにチェックされるだけで、ハイドレーション後に
    // 未チェックへ戻される(issue #1284と同種)。チェック状態ではなく、Reactの状態を反映する
    // 「N件選択中」が累計の期待値になるまで再試行する。
    const expectedCount = selectedImageIds(ctx).length + 1;
    await expect(async () => {
      if (!(await checkbox.isChecked())) {
        await checkbox.click();
      }
      await expect(page.getByText(`${expectedCount}件選択中`, { exact: true })).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 30_000 });
    selectedImageIds(ctx).push(galleryImageIds(ctx)[prompt]);
  }
);

// ---- ギャラリーの虫眼鏡ボタンとカードのクリック選択(issue #1614) ----

When(
  /^ギャラリーでプロンプト「([^」]+)」の画像の虫眼鏡ボタンを押す$/,
  async ({ page }, prompt: string) => {
    const button = page.getByRole('button', { name: `${prompt}の詳細を表示` });
    await expect(button).toBeVisible({ timeout: 30_000 });
    // ハイドレーション前のクリックは取りこぼされるので、詳細が開くまで再試行する(#1284)。
    await expect(async () => {
      await button.click();
      await expect(page.getByText('生成画像の詳細')).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 30_000 });
  }
);

When(
  /^ギャラリーでプロンプト「([^」]+)」の画像のサムネイルをクリックする$/,
  async ({ ctx, page }, prompt: string) => {
    const thumbnail = page.getByAltText(prompt, { exact: true });
    await expect(thumbnail).toBeVisible({ timeout: 30_000 });
    // ハイドレーション完了前のクリックは何も起こさない。「N件選択中」が増えるまで再試行する。
    const before = selectedImageIds(ctx).length;
    await expect(async () => {
      if (!(await selectedCountText(page, before + 1).isVisible())) {
        await thumbnail.click();
      }
      await expect(selectedCountText(page, before + 1)).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 30_000 });
    selectedImageIds(ctx).push(galleryImageIds(ctx)[prompt]);
  }
);

Then(
  /^ギャラリーのプロンプト「([^」]+)」の画像のチェックボックスは(オン|オフ)である$/,
  async ({ page }, prompt: string, state: string) => {
    const checkbox = page.getByLabel(`${prompt}を選択`);
    if (state === 'オン') await expect(checkbox).toBeChecked({ timeout: 15_000 });
    else await expect(checkbox).not.toBeChecked({ timeout: 15_000 });
  }
);

Then('詳細モーダルは開いていない', async ({ page }) => {
  await expect(page.getByText('生成画像の詳細')).toHaveCount(0);
});

Then(/^ギャラリーの選択件数に「([^」]+)」が表示される$/, async ({ page }, text: string) => {
  await expect(page.getByText(text, { exact: true })).toBeVisible({ timeout: 15_000 });
});

When('ギャラリーで全選択する', async ({ page }) => {
  await page.getByRole('button', { name: '全選択', exact: true }).click();
});

When('ギャラリーで全選択を解除する', async ({ page }) => {
  await page.getByRole('button', { name: '全選択解除', exact: true }).click();
});

Then('ギャラリーの選択件数は表示中の画像の枚数と一致する', async ({ page }) => {
  const displayed = await page.locator('input[type="checkbox"][aria-label$="を選択"]').count();
  expect(displayed, '表示中の画像が1枚も無い').toBeGreaterThan(0);
  await expect(page.getByText(`${displayed}件選択中`, { exact: true })).toBeVisible({ timeout: 15_000 });
});

Then('選択した件数の削除ボタンは押せない', async ({ page }) => {
  await expect(page.getByRole('button', { name: BULK_DELETE_BUTTON })).toBeDisabled();
});

When('選択した画像の一括削除を押して確認をキャンセルする', async ({ ctx, page }) => {
  page.once('dialog', (dialog) => {
    ctx.mediaDialogMessage = dialog.message();
    void dialog.dismiss();
  });
  await page.getByRole('button', { name: BULK_DELETE_BUTTON }).click();
  await expect.poll(() => ctx.mediaDialogMessage, { timeout: 15_000 }).toBeTruthy();
});

When('選択した画像の一括削除を押して確認を承諾する', async ({ page }) => {
  page.once('dialog', (dialog) => void dialog.accept());
  await page.getByRole('button', { name: BULK_DELETE_BUTTON }).click();
});

Then(/^確認ダイアログに「([^」]+)」と表示されていた$/, async ({ ctx }, text: string) => {
  expect(ctx.mediaDialogMessage as string).toContain(text);
});

Then(/^ギャラリーに「([^」]+)」と表示される$/, async ({ page }, text: string) => {
  await expect(page.getByRole('status').filter({ hasText: text })).toBeVisible({ timeout: 30_000 });
});

Then('ギャラリーで選択した画像のファイルは取得できる', async ({ ctx, request }) => {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  for (const id of selectedImageIds(ctx)) {
    expect(await generatedImageFileStatus(request, headers, id), `画像 ${id} のファイルが取得できない`).toBe(200);
  }
});

Then('一括削除した画像のファイルはもう取得できない', async ({ ctx, request }) => {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const ids = selectedImageIds(ctx);
  expect(ids.length, '選択した画像が記録されていない').toBeGreaterThan(0);
  for (const id of ids) {
    expect(
      await generatedImageFileStatus(request, headers, id),
      `一括削除したはずの画像 ${id} のファイルが取得できてしまいました`
    ).toBe(404);
  }
});

Then(
  /^プロンプト「([^」]+)」の画像のファイルは取得できる$/,
  async ({ ctx, request }, prompt: string) => {
    const headers = { Authorization: `Bearer ${await adminToken(request)}` };
    expect(await generatedImageFileStatus(request, headers, galleryImageIds(ctx)[prompt])).toBe(200);
  }
);

/**
 * 権限の検証は画面経由ではなく API 経由で行う。ギャラリー画面は projectId 無しの一覧取得をするため、
 * 実質 admin 専用で、一般利用者は画面からこの経路に到達できない。
 */
async function createBulkProject(request: APIRequestContext, label: string): Promise<number> {
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const created = await request.post('/api/projects', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { name: `E2E 1492 ${label} ${suffix}`, slug: `e2e-1492-${label}-${suffix}` },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return ((await created.json()) as { id: number }).id;
}

Given(
  '一般利用者が片方だけのメンバーである2つのプロジェクトにそれぞれ生成画像がある',
  async ({ ctx, request }) => {
    const headers = { Authorization: `Bearer ${await adminToken(request)}` };
    const ownProjectId = await createBulkProject(request, 'own');
    const otherProjectId = await createBulkProject(request, 'other');
    ctx.mediaBulkProjectIds = [ownProjectId, otherProjectId];

    const me = await request.get('/api/identity/me', {
      headers: { Authorization: `Bearer ${await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD)}` },
    });
    expect(me.ok(), `一般利用者の情報を取得できませんでした (status=${me.status()})`).toBe(true);
    const memberUserId = ((await me.json()) as { id: number }).id;
    const added = await request.post(`/api/projects/${ownProjectId}/users`, {
      headers,
      data: { userId: memberUserId, wpRole: 'editor' },
    });
    expect(
      added.ok(),
      `プロジェクトメンバーの追加に失敗しました (status=${added.status()}): ${await added.text()}`
    ).toBe(true);
    ctx.mediaBulkMember = { projectId: ownProjectId, userId: memberUserId };

    const ownImageId = await createGeneratedImage(request, {
      prompt: 'E2E gallery bulk own project',
      provider: 'COMFYUI',
      projectId: ownProjectId,
    });
    const otherImageId = await createGeneratedImage(request, {
      prompt: 'E2E gallery bulk other project',
      provider: 'COMFYUI',
      projectId: otherProjectId,
    });
    ctx.mediaBulkImageIds = [ownImageId, otherImageId];
  }
);

When('一般利用者が両方の生成画像を指定して一括削除を要求する', async ({ ctx, request }) => {
  const token = await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
  const response = await request.post('/api/generated-images/bulk-delete', {
    headers: { Authorization: `Bearer ${token}` },
    data: { imageIds: ctx.mediaBulkImageIds },
  });
  ctx.mediaBulkStatus = response.status();
  ctx.mediaBulkBody = await response.text();
});

Then('一括削除は一般利用者への403で拒否される', async ({ ctx }) => {
  expect(
    ctx.mediaBulkStatus,
    `権限の無い画像を含む一括削除が403で拒否されていない: ${ctx.mediaBulkBody as string}`
  ).toBe(403);
});

Then('要求に含まれた生成画像はどちらも削除されていない', async ({ ctx, request }) => {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  for (const id of ctx.mediaBulkImageIds as number[]) {
    expect(
      await generatedImageFileStatus(request, headers, id),
      `403で拒否されたはずなのに画像 ${id} が削除されている(権限のある画像も含めて1枚も消えてはならない)`
    ).toBe(200);
  }
});

After({ tags: '@media' }, async ({ ctx, request }) => {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const imageIds = [
    ...Object.values(galleryImageIds(ctx)),
    ...((ctx.mediaBulkImageIds as number[] | undefined) ?? []),
  ];
  for (const id of imageIds) {
    await request.delete(`/api/generated-images/${id}`, { headers });
  }
  const member = ctx.mediaBulkMember as { projectId: number; userId: number } | undefined;
  if (member) {
    await request.delete(`/api/projects/${member.projectId}/users/${member.userId}`, { headers });
  }
  for (const projectId of (ctx.mediaBulkProjectIds as number[] | undefined) ?? []) {
    await request.delete(`/api/projects/${projectId}`, { headers });
  }
});

// ---- ギャラリーの入れ子フォルダ(issue #1493) ----

interface FolderDto {
  id: number;
  name: string;
  parentId: number | null;
}

/**
 * このシナリオで使うフォルダの実名。フォルダの削除は #1494 の範囲でこのIssueでは後片付けできず、
 * 並列に走る他のシナリオや過去の実行のフォルダと名前がぶつからないよう、実行ごとの接尾辞を付ける。
 */
function folderName(ctx: Record<string, unknown>, label: string): string {
  ctx.mediaFolderRun ??= `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  return `${label}-${ctx.mediaFolderRun as string}`;
}

function folderIds(ctx: Record<string, unknown>): Record<string, number> {
  ctx.mediaFolderIds ??= {};
  return ctx.mediaFolderIds as Record<string, number>;
}

async function apiCreateFolder(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  label: string,
  parentLabel: string | null
): Promise<void> {
  const response = await request.post('/api/generated-images/folders', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: {
      name: folderName(ctx, label),
      parentId: parentLabel === null ? null : folderIds(ctx)[parentLabel],
    },
  });
  expect(
    response.status(),
    `フォルダの作成に失敗しました: ${await response.text()}`
  ).toBe(201);
  folderIds(ctx)[label] = ((await response.json()) as FolderDto).id;
}

async function listFolders(request: APIRequestContext, token: string): Promise<FolderDto[]> {
  const response = await request.get('/api/generated-images/folders', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.status(), `フォルダ一覧の取得に失敗しました: ${await response.text()}`).toBe(200);
  return (await response.json()) as FolderDto[];
}

Given(/^最上位フォルダ「([^」]+)」がある$/, async ({ ctx, request }, label: string) => {
  await apiCreateFolder(request, ctx, label, null);
});

Given(
  /^フォルダ「([^」]+)」の子としてフォルダ「([^」]+)」がある$/,
  async ({ ctx, request }, parentLabel: string, label: string) => {
    await apiCreateFolder(request, ctx, label, parentLabel);
  }
);

Given(
  /^フォルダ「([^」]+)」に属するプロンプト「([^」]+)」の生成画像がある$/,
  async ({ ctx, request }, folderLabel: string, prompt: string) => {
    const id = await createGeneratedImage(request, { prompt, provider: 'COMFYUI', seed: 1_493_000 });
    galleryImageIds(ctx)[prompt] = id;
    const response = await request.put(`/api/generated-images/${id}/folder`, {
      headers: { Authorization: `Bearer ${await adminToken(request)}` },
      data: { folderId: folderIds(ctx)[folderLabel] },
    });
    expect(response.status(), `画像のフォルダ設定に失敗しました: ${await response.text()}`).toBe(200);
  }
);

/** ツリー内の、名前が完全一致するフォルダ(treeitem)。 */
function folderItem(scope: Page | Locator, name: string): Locator {
  return scope.getByRole('treeitem', { name, exact: true });
}

When(
  /^ギャラリーでフォルダ「([^」]+)」を親にしてフォルダ「([^」]+)」を作成する$/,
  async ({ ctx, page }, parentLabel: string, label: string) => {
    // 親は事前にAPIで作ってある。ツリーに現れるまでが「作成」の観測点になる。
    const parentName = folderName(ctx, parentLabel);
    await expect(folderItem(page, parentName)).toBeVisible({ timeout: 30_000 });
    // ハイドレーション完了前の入力・クリックは取りこぼされる(issue #1284と同種)ので、
    // ツリーに現れるまで再試行する。
    await expect(async () => {
      const nameInput = page.getByLabel('新しいフォルダの名前');
      await nameInput.fill(folderName(ctx, label), { timeout: 2_000 });
      await page.getByLabel('親フォルダ').selectOption({ label: parentName }, { timeout: 2_000 });
      // ハイドレーションが入力を空に戻すことがある。値が残っているときだけクリックし、
      // クリックは上限付きにして、失敗時にtoPassの再試行へ戻す(無限に待たない)。
      await expect(nameInput).toHaveValue(folderName(ctx, label), { timeout: 1_000 });
      await page.getByRole('button', { name: 'フォルダを作成' }).click({ timeout: 2_000 });
      await expect(folderItem(page, folderName(ctx, label))).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 30_000 });
  }
);

Then(
  /^ギャラリーのフォルダツリーで「([^」]+)」は「([^」]+)」の子として表示される$/,
  async ({ ctx, page }, label: string, parentLabel: string) => {
    const parent = folderItem(page, folderName(ctx, parentLabel));
    const child = folderItem(parent, folderName(ctx, label));
    await expect(child).toBeVisible({ timeout: 30_000 });
    await expect(child).toHaveAttribute('aria-level', '2');
  }
);

Then(
  /^ギャラリーを開き直してもフォルダツリーで「([^」]+)」は「([^」]+)」の子として表示される$/,
  async ({ ctx, page }, label: string, parentLabel: string) => {
    await page.goto(IMAGE_GALLERY_PATH, { waitUntil: 'commit' });
    const parent = folderItem(page, folderName(ctx, parentLabel));
    const child = folderItem(parent, folderName(ctx, label));
    await expect(child).toBeVisible({ timeout: 30_000 });
    await expect(child).toHaveAttribute('aria-level', '2');
  }
);

async function selectFolderInTree(page: Page, name: string): Promise<void> {
  const item = folderItem(page, name);
  await expect(item).toBeVisible({ timeout: 30_000 });
  await expect(async () => {
    await item.getByRole('button', { name, exact: true }).first().click();
    await expect(item).toHaveAttribute('aria-selected', 'true', { timeout: 3_000 });
  }).toPass({ timeout: 30_000 });
}

When(/^ギャラリーでフォルダ「([^」]+)」を選ぶ$/, async ({ ctx, page }, label: string) => {
  await selectFolderInTree(page, folderName(ctx, label));
});

When('ギャラリーのフォルダで未分類を選ぶ', async ({ page }) => {
  await selectFolderInTree(page, '未分類');
});

When('ギャラリーのフォルダですべてを選ぶ', async ({ page }) => {
  await selectFolderInTree(page, 'すべて');
});

/** 所属フォルダの選択肢の表示名。入れ子の深さぶんだけ接頭辞が付くため、最上位以外は部分一致で探す。 */
When(
  /^詳細で所属フォルダとしてフォルダ「([^」]+)」を選ぶ$/,
  async ({ ctx, page }, label: string) => {
    const select = page.getByLabel('所属フォルダ');
    await expect(select).toBeVisible({ timeout: 30_000 });
    const id = String(folderIds(ctx)[label]);
    await expect(async () => {
      await select.selectOption(id);
      await expect(select).toHaveValue(id, { timeout: 3_000 });
    }).toPass({ timeout: 30_000 });
  }
);

When('詳細で所属フォルダを未分類に戻す', async ({ page }) => {
  const select = page.getByLabel('所属フォルダ');
  await expect(select).toBeVisible({ timeout: 30_000 });
  await expect(async () => {
    await select.selectOption('');
    await expect(select).toHaveValue('', { timeout: 3_000 });
  }).toPass({ timeout: 30_000 });
});

Then(
  /^詳細の所属フォルダにフォルダ「([^」]+)」が表示される$/,
  async ({ ctx, page }, label: string) => {
    await expect(page.getByLabel('所属フォルダ')).toHaveValue(String(folderIds(ctx)[label]), {
      timeout: 30_000,
    });
  }
);

Then(/^詳細の所属フォルダに「未分類」が表示される$/, async ({ page }) => {
  const select = page.getByLabel('所属フォルダ');
  await expect(select).toHaveValue('', { timeout: 30_000 });
  await expect(select.locator('option:checked')).toHaveText('未分類');
});

async function putFolderParent(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  token: string,
  label: string,
  parentId: number | null
): Promise<void> {
  const response = await request.put(
    `/api/generated-images/folders/${folderIds(ctx)[label]}/parent`,
    { headers: { Authorization: `Bearer ${token}` }, data: { parentId } }
  );
  ctx.mediaFolderStatuses = [...((ctx.mediaFolderStatuses as number[] | undefined) ?? []), response.status()];
}

When(
  /^管理者がフォルダ「([^」]+)」の親にフォルダ「([^」]+)」自身を指定する$/,
  async ({ ctx, request }, label: string, parentLabel: string) => {
    ctx.mediaFolderStatuses = [];
    await putFolderParent(request, ctx, await adminToken(request), label, folderIds(ctx)[parentLabel]);
  }
);

When(
  /^管理者がフォルダ「([^」]+)」の親にフォルダ「([^」]+)」を指定する$/,
  async ({ ctx, request }, label: string, parentLabel: string) => {
    ctx.mediaFolderStatuses = [];
    await putFolderParent(request, ctx, await adminToken(request), label, folderIds(ctx)[parentLabel]);
  }
);

Then('フォルダの親の変更は409で拒否される', async ({ ctx }) => {
  expect(ctx.mediaFolderStatuses, '循環を作る親の変更が409で拒否されていない').toEqual([409]);
});

Then(/^フォルダ「([^」]+)」は最上位のままである$/, async ({ ctx, request }, label: string) => {
  const folders = await listFolders(request, await adminToken(request));
  const folder = folders.find((f) => f.id === folderIds(ctx)[label]);
  expect(folder, `フォルダ「${label}」が一覧に無い`).toBeDefined();
  expect(folder?.parentId).toBeNull();
});

Then(
  /^フォルダ「([^」]+)」の親はフォルダ「([^」]+)」のままである$/,
  async ({ ctx, request }, label: string, parentLabel: string) => {
    const folders = await listFolders(request, await adminToken(request));
    const folder = folders.find((f) => f.id === folderIds(ctx)[label]);
    expect(folder?.parentId).toBe(folderIds(ctx)[parentLabel]);
  }
);

async function userToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
}

function recordFolderStatus(ctx: Record<string, unknown>, status: number): void {
  ctx.mediaFolderStatuses = [...((ctx.mediaFolderStatuses as number[] | undefined) ?? []), status];
}

When('一般利用者がフォルダを作成しようとする', async ({ ctx, request }) => {
  ctx.mediaFolderStatuses = [];
  const response = await request.post('/api/generated-images/folders', {
    headers: { Authorization: `Bearer ${await userToken(request)}` },
    data: { name: folderName(ctx, '一般利用者作成'), parentId: null },
  });
  recordFolderStatus(ctx, response.status());
});

When(
  /^一般利用者がフォルダ「([^」]+)」の親にフォルダ「([^」]+)」を指定する$/,
  async ({ ctx, request }, label: string, parentLabel: string) => {
    await putFolderParent(request, ctx, await userToken(request), label, folderIds(ctx)[parentLabel]);
  }
);

When(
  /^一般利用者がその生成画像をフォルダ「([^」]+)」へ入れようとする$/,
  async ({ ctx, request }, label: string) => {
    const response = await request.put(`/api/generated-images/${ctx.mediaImageId as number}/folder`, {
      headers: { Authorization: `Bearer ${await userToken(request)}` },
      data: { folderId: folderIds(ctx)[label] },
    });
    recordFolderStatus(ctx, response.status());
  }
);

Then('フォルダの作成・親の変更・画像の所属変更はどれも一般利用者への403で拒否される', async ({ ctx }) => {
  expect(ctx.mediaFolderStatuses, '一般利用者の変更要求(作成・親の変更・所属変更)が全て403で拒否されていない').toEqual([
    403, 403, 403,
  ]);
});

Then('その生成画像はどのフォルダにも属していない', async ({ ctx, request }) => {
  const response = await request.get(`/api/generated-images/${ctx.mediaImageId as number}`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(response.status()).toBe(200);
  expect(((await response.json()) as { folderId: number | null }).folderId).toBeNull();
});

Then(
  /^一般利用者はフォルダ一覧を取得でき、フォルダ「([^」]+)」と「([^」]+)」が含まれる$/,
  async ({ ctx, request }, labelA: string, labelB: string) => {
    const ids = (await listFolders(request, await userToken(request))).map((f) => f.id);
    expect(ids).toContain(folderIds(ctx)[labelA]);
    expect(ids).toContain(folderIds(ctx)[labelB]);
  }
);

// ---- ギャラリーのフォルダの改名・削除(issue #1494) ----

When(
  /^ギャラリーでフォルダ「([^」]+)」を「([^」]+)」に改名する$/,
  async ({ ctx, page }, label: string, newLabel: string) => {
    const oldName = folderName(ctx, label);
    const newName = folderName(ctx, newLabel);
    await expect(folderItem(page, oldName)).toBeVisible({ timeout: 30_000 });
    // ハイドレーション完了前のクリックは取りこぼされるので、新しい名前がツリーに現れるまで再試行する。
    await expect(async () => {
      if (!(await page.getByLabel(`「${oldName}」の新しい名前`).isVisible())) {
        await page.getByRole('button', { name: `「${oldName}」を改名`, exact: true }).click({ timeout: 2_000 });
      }
      const input = page.getByLabel(`「${oldName}」の新しい名前`);
      await input.fill(newName, { timeout: 2_000 });
      await expect(input).toHaveValue(newName, { timeout: 1_000 });
      await page.getByRole('button', { name: '改名を保存' }).click({ timeout: 2_000 });
      await expect(folderItem(page, newName)).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 30_000 });
  }
);

Then(/^ギャラリーのフォルダツリーにフォルダ「([^」]+)」が表示される$/, async ({ ctx, page }, label: string) => {
  await expect(folderItem(page, folderName(ctx, label))).toBeVisible({ timeout: 30_000 });
});

Then(/^ギャラリーのフォルダツリーにフォルダ「([^」]+)」は表示されない$/, async ({ ctx, page }, label: string) => {
  await expect(folderItem(page, folderName(ctx, label))).toHaveCount(0, { timeout: 30_000 });
});

/** 削除ボタンを押し、確認ダイアログの文言を記録して、承諾またはキャンセルする。 */
async function deleteFolderThroughDialog(
  page: Page,
  ctx: Record<string, unknown>,
  label: string,
  accept: boolean
): Promise<void> {
  const name = folderName(ctx, label);
  await expect(folderItem(page, name)).toBeVisible({ timeout: 30_000 });
  const messages: string[] = [];
  ctx.mediaFolderConfirmMessages = messages;
  page.on('dialog', (dialog) => {
    messages.push(dialog.message());
    void (accept ? dialog.accept() : dialog.dismiss());
  });
  // ハイドレーション完了前のクリックは取りこぼされるので、ダイアログが出るまで再試行する。
  await expect(async () => {
    // 既にダイアログを処理済み(承諾で削除済みのためボタンも消えている)なら再クリックしない。
    if (messages.length === 0) {
      await page.getByRole('button', { name: `「${name}」を削除`, exact: true }).click({ timeout: 2_000 });
    }
    await expect.poll(() => messages.length, { timeout: 2_000 }).toBeGreaterThan(0);
  }).toPass({ timeout: 30_000 });
}

When(
  /^ギャラリーでフォルダ「([^」]+)」を削除し、確認ダイアログを承諾する$/,
  async ({ ctx, page }, label: string) => {
    await deleteFolderThroughDialog(page, ctx, label, true);
  }
);

When(
  /^ギャラリーでフォルダ「([^」]+)」を削除し、確認ダイアログをキャンセルする$/,
  async ({ ctx, page }, label: string) => {
    await deleteFolderThroughDialog(page, ctx, label, false);
  }
);

Then(
  /^削除の確認に子孫フォルダ(\d+)個と画像(\d+)枚が表示され、元に戻せないと明示される$/,
  async ({ ctx }, folderCount: string, imageCount: string) => {
    const messages = (ctx.mediaFolderConfirmMessages as string[]) ?? [];
    expect(messages, '確認ダイアログが1回だけ出ていない').toHaveLength(1);
    expect(messages[0]).toContain(`子孫フォルダ${folderCount}個`);
    expect(messages[0]).toContain(`画像${imageCount}枚`);
    expect(messages[0]).toContain('元に戻せません');
  }
);

async function folderExists(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  label: string
): Promise<boolean> {
  const folders = await listFolders(request, await adminToken(request));
  return folders.some((f) => f.id === folderIds(ctx)[label]);
}

Then(/^フォルダ「([^」]+)」は削除されている$/, async ({ ctx, request }, label: string) => {
  await expect.poll(() => folderExists(request, ctx, label), { timeout: 30_000 }).toBe(false);
});

Then(/^フォルダ「([^」]+)」は削除されていない$/, async ({ ctx, request }, label: string) => {
  expect(await folderExists(request, ctx, label), `フォルダ「${label}」が消えている`).toBe(true);
});

Then(
  /^プロンプト「([^」]+)」の画像はフォルダ「([^」]+)」に属したままである$/,
  async ({ ctx, request }, prompt: string, label: string) => {
    const response = await request.get(`/api/generated-images/${galleryImageIds(ctx)[prompt]}`, {
      headers: { Authorization: `Bearer ${await adminToken(request)}` },
    });
    expect(response.status()).toBe(200);
    expect(((await response.json()) as { folderId: number | null }).folderId).toBe(folderIds(ctx)[label]);
  }
);

When(
  /^一般利用者がフォルダ「([^」]+)」の改名・削除・削除影響範囲の取得をしようとする$/,
  async ({ ctx, request }, label: string) => {
    ctx.mediaFolderStatuses = [];
    const headers = { Authorization: `Bearer ${await userToken(request)}` };
    const base = `/api/generated-images/folders/${folderIds(ctx)[label]}`;
    recordFolderStatus(ctx, (await request.put(`${base}/name`, { headers, data: { name: '改名された' } })).status());
    recordFolderStatus(ctx, (await request.delete(base, { headers })).status());
    recordFolderStatus(ctx, (await request.get(`${base}/delete-impact`, { headers })).status());
  }
);

Then('フォルダの改名・削除・削除影響範囲の取得はどれも一般利用者への403で拒否される', async ({ ctx }) => {
  expect(ctx.mediaFolderStatuses, '一般利用者の改名・削除・影響範囲の取得が全て403で拒否されていない').toEqual([
    403, 403, 403,
  ]);
});

Then(/^フォルダ「([^」]+)」は元の名前のままである$/, async ({ ctx, request }, label: string) => {
  const folders = await listFolders(request, await adminToken(request));
  expect(folders.find((f) => f.id === folderIds(ctx)[label])?.name).toBe(folderName(ctx, label));
});

// ---- 画像設定(image-settings.feature) ----

Given('画像設定を確かめるためのプロジェクトがある', async ({ ctx, request }) => {
  // 画像設定そのものを見るので、画像生成AIは既定(ComfyUI)のままでよい。
  await createProjectWithImageProvider(request, ctx, 'COMFYUI', '936-settings');
});

When(
  /^そのプロジェクトの画像設定でnegative promptの既定値に「([^」]+)」を保存する$/,
  async ({ ctx, page }, value: string) => {
    await openProjectTab(page, ctx.mediaProjectId as number, 'AI・アセット');
    const form = page.locator('form').filter({ has: page.locator('textarea[name="defaultNegativePrompt"]') });
    await form.locator('textarea[name="defaultNegativePrompt"]').fill(value);
    await form.getByRole('button', { name: '保存', exact: true }).click();
  }
);

Then('保存しましたと表示される', async ({ page }) => {
  await expect(page.getByText('保存しました。').first()).toBeVisible({ timeout: 30_000 });
});

Then(
  /^そのプロジェクトの画像生成の既定値としてnegative prompt「([^」]+)」が返る$/,
  async ({ ctx, request }, value: string) => {
    const token = await adminToken(request);
    const response = await request.get(`/api/ai/image-options?projectId=${ctx.mediaProjectId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(
      response.ok(),
      `画像生成の既定値の取得に失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);
    const options = (await response.json()) as { defaultNegativePrompt: string | null };
    expect(options.defaultNegativePrompt).toBe(value);
  }
);

When(
  /^そのプロジェクトの画像設定でデフォルトサイズに「(\d+)」x「(\d+)」を保存する$/,
  async ({ ctx, page }, width: string, height: string) => {
    await openProjectTab(page, ctx.mediaProjectId as number, 'AI・アセット');
    const form = page
      .locator('form')
      .filter({ has: page.locator('input[name="defaultGeneratedImageWidth"]') });
    await form.locator('input[name="defaultGeneratedImageWidth"]').fill(String(width));
    await form.locator('input[name="defaultGeneratedImageHeight"]').fill(String(height));
    await form.getByRole('button', { name: '保存', exact: true }).click();
    await expect(form.getByText('保存しました。')).toBeVisible({ timeout: 30_000 });
  }
);

When(
  /^そのプロジェクトの画像設定で記事内画像のリサイズ幅に「(\d+)」を保存する$/,
  async ({ page }, longEdgePx: string) => {
    const form = page
      .locator('form')
      .filter({ has: page.locator('input[name="defaultArticleImageLongEdgePx"]') });
    await form.locator('input[name="defaultArticleImageLongEdgePx"]').fill(String(longEdgePx));
    await form.getByRole('button', { name: '保存', exact: true }).click();
    await expect(form.getByText('保存しました。')).toBeVisible({ timeout: 30_000 });
  }
);

When('そのプロジェクトの画像設定をページを開き直して表示する', async ({ ctx, page }) => {
  // #913 と同型の退行検知。保存直後の画面ではなく、**開き直した**画面を見る。
  await openProjectTab(page, ctx.mediaProjectId as number, 'AI・アセット');
});

Then(
  /^デフォルトサイズの入力には「(\d+)」と「(\d+)」が入っている$/,
  async ({ page }, width: string, height: string) => {
    await expect(page.locator('input[name="defaultGeneratedImageWidth"]')).toHaveValue(
      String(width),
      { timeout: 30_000 }
    );
    await expect(page.locator('input[name="defaultGeneratedImageHeight"]')).toHaveValue(
      String(height)
    );
  }
);

Then(
  /^記事内画像のリサイズ幅の入力には「(\d+)」が入っている$/,
  async ({ page }, longEdgePx: string) => {
    await expect(page.locator('input[name="defaultArticleImageLongEdgePx"]')).toHaveValue(
      String(longEdgePx),
      { timeout: 30_000 }
    );
  }
);

Given('そのプロジェクトで性的な画像の生成を禁止する設定が保存されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const response = await request.put(
    `/api/projects/${ctx.mediaProjectId}/image-content-filter-settings`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: { blockSexualContent: true, blockViolentContent: true, blockDiscriminatoryContent: true },
    }
  );
  expect(
    response.ok(),
    `コンテンツフィルタ設定の保存に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const saved = (await response.json()) as { blockSexualContent: boolean };
  expect(saved.blockSexualContent, '性的コンテンツの禁止が保存されていません').toBe(true);
});

Then(
  /^拒否の理由に禁止されたカテゴリ「([^」]+)」が示される$/,
  async ({ ctx }, category: string) => {
    expect(outcome(ctx).body).toContain(category);
  }
);

// ---- ComfyUIチェックポイント(comfyui-checkpoints.feature) ----

interface CheckpointList {
  checkpoints: string[];
  selected: string;
}

async function fetchCheckpoints(
  request: APIRequestContext,
  projectId: number
): Promise<CheckpointList> {
  const token = await adminToken(request);
  const response = await request.get(`/api/projects/${projectId}/ai-models/comfyui/checkpoints`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `チェックポイント一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as CheckpointList;
}

When('そのプロジェクトで利用可能なチェックポイントの一覧を取得する', async ({ ctx, request }) => {
  ctx.mediaCheckpoints = await fetchCheckpoints(request, ctx.mediaProjectId as number);
});

Then('チェックポイントが1件以上返る', async ({ ctx }) => {
  const list = ctx.mediaCheckpoints as CheckpointList;
  expect(list.checkpoints.length, 'ComfyUIから利用可能なチェックポイントが1件も返りません').toBeGreaterThan(0);
});

When('一覧の先頭のチェックポイントを選択する', async ({ ctx, request }) => {
  const list = ctx.mediaCheckpoints as CheckpointList;
  const target = list.checkpoints[0];
  const token = await adminToken(request);
  const response = await request.put(
    `/api/projects/${ctx.mediaProjectId}/ai-models/comfyui/checkpoints/selection`,
    { headers: { Authorization: `Bearer ${token}` }, data: { checkpointName: target } }
  );
  expect(
    response.ok(),
    `チェックポイントの選択に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.mediaSelectedCheckpoint = target;
});

Then('そのプロジェクトの選択中チェックポイントは選択したものになる', async ({ ctx, request }) => {
  const list = await fetchCheckpoints(request, ctx.mediaProjectId as number);
  expect(list.selected).toBe(ctx.mediaSelectedCheckpoint);
});

/**
 * ブラウザ/プロフィールのタイムゾーン差によるハイドレーション不一致(issue #1236)。
 *
 * `ImageGalleryGrid` は生成日時を `formatDateTime` でロケール整形して表示する。修正前は
 * オフセット無しの日時文字列(バックエンドのLocalDateTime由来)を実行環境のローカルタイムと
 * して解釈していたため、SSR(コンテナ、TZ=UTC)とブラウザで表示結果が食い違い、
 * ハイドレーション不一致が発生し得た。ブラウザTZをプロフィールTZと意図的に違えて開き、
 * コンソールにハイドレーションエラーが記録されないことを確かめる。
 *
 * ブラウザTZは Playwright の `newContext({ timezoneId })` でしか指定できない(既存の
 * `page` は生成済みのコンテキストに属し、後から変更できない)ため、ここだけ専用の
 * `BrowserContext` / `Page` を作り、その中でログインする。
 */
Given(/^個人設定のタイムゾーンを「([^」]+)」に変更する$/, async ({ ctx, request }, timezone: string) => {
  const token = await adminToken(request);
  const me = await request.get('/api/identity/me', { headers: { Authorization: `Bearer ${token}` } });
  expect(me.ok(), `自ユーザー情報の取得に失敗しました (status=${me.status()})`).toBe(true);
  const profile = (await me.json()) as { locale: string | null; timezone: string | null };
  ctx.mediaOriginalTimezone = profile.timezone;
  ctx.mediaOriginalLocale = profile.locale;
  const response = await request.patch('/api/identity/me/preferences', {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { locale: profile.locale ?? 'ja', timezone },
  });
  expect(
    response.ok(),
    `タイムゾーンの変更に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

When(
  /^ブラウザのタイムゾーンを「([^」]+)」にして管理者としてログインし、画像ギャラリーを開く$/,
  async ({ ctx, page }, timezoneId: string) => {
    const browser = page.context().browser();
    if (!browser) {
      throw new Error('ブラウザインスタンスを取得できない(TZ指定のコンテキストを作成できない)');
    }
    const tzContext = await browser.newContext({ ignoreHTTPSErrors: true, timezoneId });
    const tzPage = await tzContext.newPage();
    const consoleErrors: string[] = [];
    tzPage.on('console', (msg) => {
      if (msg.type() === 'error') {
        consoleErrors.push(msg.text());
      }
    });
    tzPage.on('pageerror', (err) => {
      consoleErrors.push(err.message);
    });
    ctx.mediaTzContext = tzContext;
    ctx.mediaTzConsoleErrors = consoleErrors;

    await loginViaKeycloak(tzPage, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    await tzPage.goto(IMAGE_GALLERY_PATH, { waitUntil: 'networkidle' });
    await expect(tzPage.getByRole('heading', { name: '画像ギャラリー' })).toBeVisible({ timeout: 30_000 });
    // ハイドレーション後の再描画にも猶予を見る(#1236の症状は再描画のタイミングで起きる)。
    await tzPage.waitForTimeout(1000);
  }
);

/** ハイドレーション関連のReact/Next.jsエラーに必ず現れる文言(大文字小文字を区別しない)。 */
const HYDRATION_ERROR_MARKER = /hydrat/i;

Then('コンソールにハイドレーションエラーが記録されない', async ({ ctx }) => {
  const consoleErrors = (ctx.mediaTzConsoleErrors as string[] | undefined) ?? [];
  const hydrationErrors = consoleErrors.filter((text) => HYDRATION_ERROR_MARKER.test(text));
  expect(
    hydrationErrors,
    `ハイドレーションエラーが記録されている(issue #1236):\n  ${hydrationErrors.join('\n  ')}`
  ).toEqual([]);
});

After({ tags: '@media' }, async ({ ctx, request }) => {
  const tzContext = ctx.mediaTzContext as BrowserContext | undefined;
  if (tzContext) {
    await tzContext.close();
  }
  if (ctx.mediaOriginalTimezone !== undefined) {
    const token = await adminToken(request);
    await request.patch('/api/identity/me/preferences', {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        locale: (ctx.mediaOriginalLocale as string | null) ?? 'ja',
        timezone: (ctx.mediaOriginalTimezone as string | null) ?? 'Asia/Tokyo',
      },
    });
  }
});

Then('パネル内の全てのラベルが対応する入力と結びついている', async ({ page }) => {
  // htmlFor が対応する id の要素を指していないラベルの数を、パネルの描画結果から数える(issue #1114)。
  const result = await page.evaluate(() => {
    const heading = Array.from(document.querySelectorAll('h1,h2,h3,h4')).find(
      (h) => h.textContent?.trim() === 'アセット画像生成'
    );
    // 見出しは「見出し+閉じる」だけの div に入っているので、パネル全体である section まで遡る。
    const root = heading?.closest('section');
    const labels = root ? Array.from(root.querySelectorAll('label')) : [];
    return {
      total: labels.length,
      unbound: labels
        .filter((l) => !l.htmlFor || document.getElementById(l.htmlFor) !== l.control)
        .map((l) => l.textContent?.trim()),
    };
  });
  // ラベルを1つも拾えないまま通る(空振り)のを防ぐ。パネルの常設ラベルは10個以上ある。
  expect(result.total).toBeGreaterThanOrEqual(10);
  expect(result.unbound).toEqual([]);
});

/**
 * 画像生成のジョブとしての非同期受理(issue #1405)のステップ定義。
 *
 * `POST /api/ai/image/jobs` は upload-endpoint 枠に入らない(api-global)ため、
 * 同期APIのステップと違い枠(10/時)を消費しない。
 */

interface ImageJobState {
  acceptStatus: number;
  acceptBody: string;
  jobId: number | null;
  jobStatus: string | null;
  final: { status: string; resultPayload: string | null } | null;
}

function jobState(ctx: Record<string, unknown>): ImageJobState {
  const value = ctx.imageJob as ImageJobState | undefined;
  expect(value, '画像生成のジョブがまだ要求されていません').toBeDefined();
  return value as ImageJobState;
}

When(
  /^そのプロジェクトで「([^」]+)」の画像生成をジョブとして要求する$/,
  async ({ ctx, request }, prompt: string) => {
    const token = await adminToken(request);
    const response = await request.post('/api/ai/image/jobs', {
      headers: { Authorization: `Bearer ${token}` },
      data: { prompt, projectId: ctx.mediaProjectId },
      timeout: 30_000,
    });
    const text = await response.text();
    let parsed: { id?: number; status?: string } = {};
    try {
      parsed = JSON.parse(text) as { id?: number; status?: string };
    } catch {
      // 本文がJSONでなければ後続の検証がステータスと本文を出して失敗する。
    }
    ctx.imageJob = {
      acceptStatus: response.status(),
      acceptBody: text,
      jobId: parsed.id ?? null,
      jobStatus: parsed.status ?? null,
      final: null,
    } satisfies ImageJobState;
  }
);

Then(
  /^画像生成のジョブIDが「(\w+)」の状態で即座に返る$/,
  async ({ ctx }, status: string) => {
    const job = jobState(ctx);
    expect(job.acceptStatus, `応答本文: ${job.acceptBody}`).toBe(202);
    expect(job.jobId, `ジョブIDが返っていません: ${job.acceptBody}`).not.toBeNull();
    expect(job.jobStatus).toBe(status);
  }
);

When('画像生成のジョブが終わるまで待つ', async ({ ctx, request }) => {
  const job = jobState(ctx);
  expect(job.jobId, `ジョブIDが返っていません: ${job.acceptBody}`).not.toBeNull();
  const token = await adminToken(request);
  const deadline = Date.now() + 120_000;
  let last = { status: 'running', resultPayload: null as string | null };
  while (Date.now() < deadline) {
    const response = await request.get(`/api/generation-jobs/${job.jobId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(
      response.ok(),
      `ジョブの取得に失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);
    last = (await response.json()) as { status: string; resultPayload: string | null };
    if (last.status !== 'running') {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  job.final = last;
});

Then(
  /^そのジョブは「done」で終わり、結果に生成画像のIDが「(\d+)」件示される$/,
  async ({ ctx }, count: string) => {
    const final = jobState(ctx).final;
    expect(final, 'ジョブの終了を待っていません').not.toBeNull();
    expect(final?.status, `結果: ${final?.resultPayload}`).toBe('done');
    const result = JSON.parse(final?.resultPayload ?? '{}') as { imageIds?: number[] };
    expect(result.imageIds).toHaveLength(Number(count));
    expect(final?.resultPayload).not.toContain('dataBase64');
    ctx.mediaGeneratedIds = result.imageIds ?? [];
  }
);

Then('結果が示す画像はすべて生成画像の一覧に現れる', async ({ ctx, request }) => {
  const ids = await listGeneratedImageIds(request);
  const shown = (ctx.mediaGeneratedIds as number[] | undefined) ?? [];
  expect(shown.length).toBeGreaterThan(0);
  for (const id of shown) {
    expect(ids, `生成画像 ${id} が一覧に現れていません`).toContain(id);
  }
});

Then(
  /^そのジョブは「failed」で終わり、理由に失敗した画像生成AIと状態コード「(\d+)」が示される$/,
  async ({ ctx }, status: string) => {
    const final = jobState(ctx).final;
    expect(final, 'ジョブの終了を待っていません').not.toBeNull();
    expect(final?.status, `結果: ${final?.resultPayload}`).toBe('failed');
    const result = JSON.parse(final?.resultPayload ?? '{}') as { error?: string; errorType?: string };
    expect(result.error).toContain('ChatGPT');
    expect(result.error).toContain(String(status));
    expect(result.errorType).toBeTruthy();
  }
);

// ---- ギャラリーのページングと無限スクロール(image-gallery-paging.feature、issue #1472) ----

/** ギャラリーの1ページぶんの件数。`apps/web/src/app/image-gallery/page.tsx` の GALLERY_PAGE_SIZE と一致させる。 */
const GALLERY_PAGE_SIZE = 24;

/** 一覧のサムネイル(詳細モーダルの画像ではなくカードグリッド(`div.grid.gap-4`)の画像)。 */
function galleryThumbnails(page: Page): Locator {
  return page.locator('div.grid.gap-4 img[src^="/image-gallery/"]');
}

/** 画面上のサムネイルのIDを、重複を残したまま取り出す。 */
async function thumbnailIds(page: Page): Promise<number[]> {
  const sources = await galleryThumbnails(page).evaluateAll((nodes) =>
    nodes.map((node) => node.getAttribute('src') ?? '')
  );
  return sources.map((src) => Number(/\/image-gallery\/(\d+)\/file/.exec(src)?.[1]));
}

/** 固定画像を古い順に作る。ctx.mediaPagingIds は作成順(先頭が最初に作った画像)。 */
async function createPagingFixtures(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  count: number,
  taggedIndexes: number[],
  tag?: string,
  providerOf: (index: number) => string = () => 'COMFYUI'
): Promise<void> {
  const ids: number[] = [];
  ctx.mediaPagingIds = ids;
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  for (let i = 0; i < count; i += 1) {
    const overrides: Record<string, unknown> = {
      prompt: `E2E paging fixture ${suffix}-${i}`,
      provider: providerOf(i),
      seed: 1_472_000 + i,
    };
    if (tag !== undefined && taggedIndexes.includes(i)) {
      overrides.tagsJson = JSON.stringify([tag]);
    }
    ids.push(await createGeneratedImage(request, overrides));
  }
}

Given(
  /^ギャラリーに固定画像の生成画像を(\d+)件作成する$/,
  async ({ ctx, request }, count: string) => {
    await createPagingFixtures(request, ctx, Number(count), []);
  }
);

Given(
  /^ギャラリーに固定画像の生成画像を(\d+)件作成し、最新と最初に作成した1件にだけタグ「([^」]+)」を付ける$/,
  async ({ ctx, request }, count: string, tag: string) => {
    const total = Number(count);
    await createPagingFixtures(request, ctx, total, [0, total - 1], tag);
  }
);

Then(/^一覧のサムネイルは(\d+)件ちょうどである$/, async ({ page }, count: string) => {
  expect(Number(count)).toBe(GALLERY_PAGE_SIZE);
  await expect(galleryThumbnails(page).first()).toBeVisible({ timeout: 30_000 });
  await expect(galleryThumbnails(page)).toHaveCount(GALLERY_PAGE_SIZE);
});

Then('最初に作成した固定画像はまだ一覧に現れていない', async ({ ctx, page }) => {
  const oldest = (ctx.mediaPagingIds as number[])[0];
  await expect(galleryThumbnails(page).first()).toBeVisible({ timeout: 30_000 });
  expect(await thumbnailIds(page)).not.toContain(oldest);
});

When('最初に作成した固定画像が現れるまで一覧の末尾までスクロールする', async ({ ctx, page }) => {
  const fixtures = ctx.mediaPagingIds as number[];
  const oldest = fixtures[0];
  // offset 方式なので、スクロール中に他のシナリオが画像を削除すると境界がずれて1件を飛ばすことがある
  // (Issue #1472 の Out of Scope で許容した取りこぼし)。共有環境では並列シナリオの後片付けで起こりうるため、
  // 固定画像が欠けていたら開き直して最初からやり直す(最大3回)。
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    await expect(galleryThumbnails(page).first()).toBeVisible({ timeout: 30_000 });
    await expect
      .poll(
        async () => {
          await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
          return (await thumbnailIds(page)).includes(oldest);
        },
        { timeout: 60_000, intervals: [300] }
      )
      .toBe(true);
    const shown = await thumbnailIds(page);
    if (fixtures.every((id) => shown.includes(id)) || attempt === 3) {
      return;
    }
    await page.reload({ waitUntil: 'commit' });
  }
});

Then(/^固定画像(\d+)件がすべて一覧にある$/, async ({ ctx, page }, count: string) => {
  const fixtures = ctx.mediaPagingIds as number[];
  expect(fixtures).toHaveLength(Number(count));
  const shown = await thumbnailIds(page);
  for (const id of fixtures) {
    expect(shown, `固定画像 ${id} が一覧にありません`).toContain(id);
  }
});

Then('同じ画像のサムネイルは1つずつしかない', async ({ page }) => {
  const shown = await thumbnailIds(page);
  expect(shown.length).toBe(new Set(shown).size);
});

When(/^タグ「([^」]+)」で絞り込む$/, async ({ page }, tag: string) => {
  await expect(galleryThumbnails(page).first()).toBeVisible({ timeout: 30_000 });
  const chip = page.getByRole('button', { name: tag, exact: true });
  // SSR 直後はまだハイドレーション前でクリックが失われる(issue #1236 と同じ事情)ので、
  // 選択状態(黒地)になるまでクリックし直す。
  await expect(async () => {
    await chip.click();
    await expect(chip).toHaveClass(/bg-neutral-900/, { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
});

When(/^カード上のタグ「([^」]+)」を押す$/, async ({ page }, tag: string) => {
  await expect(galleryThumbnails(page).first()).toBeVisible({ timeout: 30_000 });
  const cardTag = page.getByRole('button', { name: `タグ「${tag}」で絞り込む`, exact: true }).first();
  const chip = page.getByRole('button', { name: tag, exact: true });
  // SSR 直後はハイドレーション前でクリックが失われるので、チップが選択状態になるまで押し直す。
  await expect(async () => {
    await cardTag.click();
    await expect(chip).toHaveClass(/bg-neutral-900/, { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
});

Then(/^上部のタグチップ「([^」]+)」が選択表示になっている$/, async ({ page }, tag: string) => {
  await expect(page.getByRole('button', { name: tag, exact: true })).toHaveClass(/bg-neutral-900/);
});

Then('一覧に表示される固定画像は最新と最初に作成した2件だけである', async ({ ctx, page }) => {
  const fixtures = ctx.mediaPagingIds as number[];
  const expected = [fixtures[0], fixtures[fixtures.length - 1]];
  await expect
    .poll(
      async () => (await thumbnailIds(page)).filter((id) => fixtures.includes(id)).sort((a, b) => a - b),
      { timeout: 30_000 }
    )
    .toEqual(expected.sort((a, b) => a - b));
});

After({ tags: '@media' }, async ({ ctx, request }) => {
  const ids = ctx.mediaPagingIds as number[] | undefined;
  if (ids === undefined) {
    return;
  }
  const token = await adminToken(request);
  for (const id of ids) {
    await request.delete(`/api/generated-images/${id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
  }
});

// issue #1646: カードの種別アイコン。アップロード / ComfyUI / ChatGPT の画像を1件ずつ作り、各カードのアイコンを確かめる。
const SOURCE_ICON_NAMES = ['アップロード', 'AI生成(ComfyUI)', 'AI生成(ChatGPT)'];

Given('ギャラリーにアップロード画像・ComfyUI画像・ChatGPT画像が1件ずつある', async ({ ctx, request }) => {
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const ids: Record<string, number> = {};
  for (const provider of ['UPLOAD', 'COMFYUI', 'CHATGPT']) {
    ids[provider] = await createGeneratedImage(request, {
      prompt: `E2E source icon fixture ${suffix}-${provider}`,
      provider,
      seed: 1_646_000,
    });
  }
  ctx.mediaSourceIconIds = ids;
});

async function expectOnlySourceIcon(
  page: Page,
  ctx: Record<string, unknown>,
  provider: string,
  name: string
): Promise<void> {
  const id = (ctx.mediaSourceIconIds as Record<string, number>)[provider];
  const thumbnail = page.locator(`img[src="/image-gallery/${id}/file"]`);
  await expect(thumbnail).toBeVisible({ timeout: 30_000 });
  const card = thumbnail.locator('xpath=ancestor::div[contains(@class,"relative")][1]');
  await expect(card.getByRole('img', { name, exact: true })).toBeVisible();
  for (const other of SOURCE_ICON_NAMES.filter((n) => n !== name)) {
    await expect(card.getByRole('img', { name: other, exact: true })).toHaveCount(0);
  }
}

Then('アップロード画像のカードにアクセシブルネーム「アップロード」のアイコンだけが表示される', async ({ ctx, page }) => {
  await expectOnlySourceIcon(page, ctx, 'UPLOAD', 'アップロード');
});

Then('ComfyUI画像のカードにアクセシブルネーム「AI生成\\(ComfyUI)」のアイコンだけが表示される', async ({ ctx, page }) => {
  await expectOnlySourceIcon(page, ctx, 'COMFYUI', 'AI生成(ComfyUI)');
});

Then('ChatGPT画像のカードにアクセシブルネーム「AI生成\\(ChatGPT)」のアイコンだけが表示される', async ({ ctx, page }) => {
  await expectOnlySourceIcon(page, ctx, 'CHATGPT', 'AI生成(ChatGPT)');
});
