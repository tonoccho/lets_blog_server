import type { APIRequestContext, BrowserContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  expect,
  fetchAccessToken,
  loginViaKeycloak,
} from '../support';
import { STUB_URLS, forceStubStatus, resetStub, stubRequestCount } from '../support/stubs';

/**
 * 生成画像ギャラリーのseed表示のステップ定義(issue #1101)。
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

When('生成画像ギャラリーでその画像の詳細を開く', async ({ ctx, page }) => {
  await page.goto('/image-gallery', { waitUntil: 'commit' });
  await page.locator(`img[src="/image-gallery/${ctx.mediaImageId}/file"]`).click();
  await expect(page.getByText('生成画像の詳細')).toBeVisible({ timeout: 15_000 });
  await expect(detailValue(page, 'prompt')).toBeVisible({ timeout: 15_000 });
});

Then(/^詳細にseed「(\d+)」が表示される$/, async ({ page }, seed: string) => {
  await expect(detailValue(page, 'seed')).toHaveText(seed);
});

Then(/^詳細にバッチ内位置「(\d+)」が表示される$/, async ({ page }, batchIndex: string) => {
  await expect(detailValue(page, 'batch index')).toHaveText(batchIndex);
});

Then('詳細にseedの値は表示されず、再現不可と分かる表示になる', async ({ page }) => {
  await expect(detailValue(page, 'seed')).toContainText('再現不可');
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
  ctx.mediaProjectId = projectId;
});

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
    expect(result.body).toContain(limit);
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

Then(/^batch sizeの入力があり、上限は「(\d+)」である$/, async ({ page }, max: string) => {
  const input = panelNumberInput(page, `batch size(最大${max})`);
  await expect(input).toBeVisible();
  await expect(input).toHaveAttribute('max', max);
});

Then(/^batch countの入力があり、上限は「(\d+)」である$/, async ({ page }, max: string) => {
  const input = panelNumberInput(page, `batch count(最大${max})`);
  await expect(input).toBeVisible();
  await expect(input).toHaveAttribute('max', max);
});

Then('合計枚数の目安と、枚数によっては長時間かかる旨が表示される', async ({ page }) => {
  await expect(page.getByText(/この設定で合計\d+枚/)).toBeVisible();
  await expect(page.getByText(/非常に長時間かかります/)).toBeVisible();
});

When(
  /^batch sizeに「(\d+)」、batch countに「(\d+)」を入力して生成する$/,
  async ({ page }, batchSize: string, batchCount: string) => {
    await page.getByPlaceholder('生成したい画像の説明').fill('e2e 1103 asset image');
    await panelNumberInput(page, 'batch size(最大16)').fill(batchSize);
    await panelNumberInput(page, 'batch count(最大16)').fill(batchCount);
    const generate = page.getByRole('button', { name: '生成', exact: true });
    await expect(generate).toBeEnabled();
    await generate.click();
  }
);

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
  // ChatGPT スタブは1回に10枚までなので、16枚の要求はサーバー側で拒否されるのが正しい。
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

/** 生成画像ギャラリーのURL。 */
const IMAGE_GALLERY_PATH = '/image-gallery';

/**
 * 検証用に導入する小さなチェックポイント(issue #936 シナリオ13・14)。
 *
 * #936 の方針は実生成に SDXL base(約6.9GB)を使うことだが、**導入シナリオが確かめるのは
 * ダウンロードと配置が成立することだけ**なので、Implementation Notes の
 * 「小さいモデルで検証する」に従って 283KB の safetensors を使う。
 * 6.9GB を毎回落とすのは受け入れテストの所要時間として現実的でない。
 *
 * ComfyUI のチェックポイント一覧は `checkpoints/` に置かれたファイル名をそのまま返すので、
 * 中身が拡散モデルとして完全である必要は無い(このシナリオは生成を行わない)。
 */
const TINY_CHECKPOINT_URL =
  'https://huggingface.co/hf-internal-testing/tiny-sd-pipe/resolve/main/text_encoder/model.safetensors';

/** 導入先のファイル名。ComfyUiCheckpointTable の SAFE_FILE_NAME(英数字・_・-・.)に収める。 */
const TINY_CHECKPOINT_FILE_NAME = 'e2e-936-tiny.safetensors';

/** 非同期ジョブ(GenerationJob)の完了を待つときの上限。導入はダウンロードを伴う。 */
const JOB_TIMEOUT_MS = 300_000;

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

/** GenerationJob が done / failed になるまで待ち、最終状態を返す。 */
async function waitForGenerationJob(
  request: APIRequestContext,
  jobId: number
): Promise<{ status: string; resultPayload: string | null }> {
  const token = await adminToken(request);
  const deadline = Date.now() + JOB_TIMEOUT_MS;
  let last: { status: string; resultPayload: string | null } = { status: 'unknown', resultPayload: null };
  while (Date.now() < deadline) {
    const response = await request.get(`/api/generation-jobs/${jobId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(
      response.ok(),
      `ジョブの取得に失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);
    last = (await response.json()) as { status: string; resultPayload: string | null };
    if (last.status !== 'running' && last.status !== 'pending') {
      return last;
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`ジョブ ${jobId} が ${JOB_TIMEOUT_MS}ms 以内に終わりませんでした(最後の状態: ${last.status})`);
}

/** プロジェクト詳細の指定タブを開く。パネルはタブの中にあるため、これを通らないと見えない。 */
async function openProjectTab(page: Page, projectId: number, tabLabel: string): Promise<void> {
  await page.goto(`/projects/${projectId}`, { waitUntil: 'commit' });
  const tab = page.getByRole('button', { name: tabLabel, exact: true });
  await expect(tab).toBeVisible({ timeout: 30_000 });
  await tab.click();
}

// ---- 画像生成(image-generation.feature) ----

Given('画像生成にComfyUIを使うプロジェクトがある', async ({ ctx, request }) => {
  await createProjectWithImageProvider(request, ctx, 'COMFYUI', '936-comfyui');
});

When(
  /^そのプロジェクトで「([^」]+)」の画像生成を要求する$/,
  async ({ ctx, request }, prompt: string) => {
    await requestImageGeneration(request, ctx, { projectId: ctx.mediaProjectId, prompt, batchSize: 1 });
  }
);

When(
  /^そのプロジェクトで「([^」]+)」を「(\d+)」x「(\d+)」で画像生成を要求する$/,
  async ({ ctx, request }, prompt: string, width: string, height: string) => {
    await requestImageGeneration(request, ctx, {
      projectId: ctx.mediaProjectId,
      prompt,
      batchSize: 1,
      width: Number(width),
      height: Number(height),
    });
  }
);

Then(
  /^生成された画像の詳細のプロンプトに「([^」]+)」が含まれる$/,
  async ({ ctx, request }, prompt: string) => {
    const detail = await fetchGeneratedImageDetail(request, firstGeneratedImageId(ctx));
    // 画質プロンプト(プロジェクト/アプリの既定値)が末尾へ連結されるため前方一致では見ない。
    expect(detail.prompt).toContain(prompt);
  }
);

Then(
  /^生成された画像の詳細のサイズは「(\d+)」x「(\d+)」である$/,
  async ({ ctx, request }, width: string, height: string) => {
    const detail = await fetchGeneratedImageDetail(request, firstGeneratedImageId(ctx));
    expect(detail.width).toBe(Number(width));
    expect(detail.height).toBe(Number(height));
  }
);

Then('生成された画像の詳細にチェックポイント名が残っている', async ({ ctx, request }) => {
  const detail = await fetchGeneratedImageDetail(request, firstGeneratedImageId(ctx));
  expect(detail.checkpoint, '生成に使ったチェックポイントが記録されていません').toBeTruthy();
});

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

Then(
  /^生成された画像の詳細の画像生成AIは「([A-Z]+)」である$/,
  async ({ ctx, request }, provider: string) => {
    const detail = await fetchGeneratedImageDetail(request, firstGeneratedImageId(ctx));
    expect(detail.provider).toBe(provider);
  }
);

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
  }
);

When('生成画像ギャラリーを開く', async ({ page }) => {
  await page.goto(IMAGE_GALLERY_PATH, { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: '生成画像ギャラリー' })).toBeVisible({ timeout: 30_000 });
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
    await form.locator('input[name="defaultGeneratedImageWidth"]').fill(width);
    await form.locator('input[name="defaultGeneratedImageHeight"]').fill(height);
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
    await form.locator('input[name="defaultArticleImageLongEdgePx"]').fill(longEdgePx);
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
    await expect(page.locator('input[name="defaultGeneratedImageWidth"]')).toHaveValue(width, {
      timeout: 30_000,
    });
    await expect(page.locator('input[name="defaultGeneratedImageHeight"]')).toHaveValue(height);
  }
);

Then(
  /^記事内画像のリサイズ幅の入力には「(\d+)」が入っている$/,
  async ({ page }, longEdgePx: string) => {
    await expect(page.locator('input[name="defaultArticleImageLongEdgePx"]')).toHaveValue(longEdgePx, {
      timeout: 30_000,
    });
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

When('そのプロジェクトへ検証用の小さなチェックポイントを導入する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const response = await request.post(
    `/api/projects/${ctx.mediaProjectId}/ai-models/comfyui/checkpoints/install`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: { downloadUrl: TINY_CHECKPOINT_URL, fileName: TINY_CHECKPOINT_FILE_NAME },
    }
  );
  expect(
    response.ok(),
    `チェックポイントの導入開始に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const job = (await response.json()) as { id: number };
  ctx.mediaInstallJob = await waitForGenerationJob(request, job.id);
  ctx.mediaInstalledCheckpoint = TINY_CHECKPOINT_FILE_NAME;
});

Then('導入のジョブは成功で終わる', async ({ ctx }) => {
  const job = ctx.mediaInstallJob as { status: string; resultPayload: string | null };
  expect(job.status, `導入ジョブが失敗しました: ${job.resultPayload}`).toBe('done');
});

Then('導入したチェックポイントがそのプロジェクトの一覧に現れる', async ({ ctx, request }) => {
  const list = await fetchCheckpoints(request, ctx.mediaProjectId as number);
  expect(list.checkpoints).toContain(ctx.mediaInstalledCheckpoint);
});

When('そのプロジェクトの管理画面でComfyUIチェックポイントの一覧を開く', async ({ ctx, page }) => {
  // チェックポイント表は二段のタブの奥にある。プロジェクト詳細の「AI・アセット」タブを開き、
  // その中の「AIモデル管理」パネルで「画像生成」サブタブへ切り替えて初めて取得・描画される
  // (ProjectAiModelsPanel は開いたタブの分だけをクライアント側で取りに行く)。
  await openProjectTab(page, ctx.mediaProjectId as number, 'AI・アセット');
  await page.getByRole('button', { name: '画像生成', exact: true }).click();
  await expect(page.getByText('選択中のチェックポイント:')).toBeVisible({ timeout: 30_000 });
});

/** チェックポイント名の行。表の1列目がその名前である行を選ぶ。 */
function checkpointRow(page: Page, name: string) {
  return page.locator('tr').filter({ has: page.locator(`td:has-text("${name}")`) });
}

Then('選択中のチェックポイントの削除ボタンは押せず、理由が示される', async ({ page }) => {
  const selectedRow = page.locator('tr').filter({ has: page.getByText('選択中', { exact: true }) });
  const deleteButton = selectedRow.getByRole('button', { name: '削除', exact: true });
  await expect(deleteButton).toBeDisabled();
  await expect(deleteButton).toHaveAttribute('title', '選択中のチェックポイントは削除できません');
});

When('導入したチェックポイントを画面から削除する', async ({ ctx, page }) => {
  const name = ctx.mediaInstalledCheckpoint as string;
  page.once('dialog', (dialog) => dialog.accept());
  await checkpointRow(page, name).getByRole('button', { name: '削除', exact: true }).click();
  await expect(page.getByText('完了しました。')).toBeVisible({ timeout: JOB_TIMEOUT_MS });
});

Then('導入したチェックポイントが一覧から消える', async ({ ctx, request }) => {
  const list = await fetchCheckpoints(request, ctx.mediaProjectId as number);
  expect(list.checkpoints).not.toContain(ctx.mediaInstalledCheckpoint);
});

/**
 * 導入したチェックポイントは ComfyUI のモデル領域に残るので、必ず消す。
 * このボリューム(`comfyui_models`)はゼロ構築でも**保全される**(モデルの再取得が
 * 現実的でないため。docs/ACCEPTANCE_TESTING.md §10)ので、後片付けをしないと
 * 実行のたびに増え続ける。
 */
After({ tags: '@media' }, async ({ ctx, request }) => {
  const projectId = ctx.mediaProjectId as number | undefined;
  if (ctx.mediaInstalledCheckpoint === undefined || projectId === undefined) {
    return;
  }
  const token = await adminToken(request);
  const response = await request.delete(
    `/api/projects/${projectId}/ai-models/comfyui/checkpoints/${ctx.mediaInstalledCheckpoint as string}`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  if (response.ok()) {
    await waitForGenerationJob(request, ((await response.json()) as { id: number }).id);
  }
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
  /^ブラウザのタイムゾーンを「([^」]+)」にして管理者としてログインし、生成画像ギャラリーを開く$/,
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
    await expect(tzPage.getByRole('heading', { name: '生成画像ギャラリー' })).toBeVisible({ timeout: 30_000 });
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
