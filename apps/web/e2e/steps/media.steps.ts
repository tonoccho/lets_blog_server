import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

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
  await page.goto(`/projects/${ctx.mediaProjectId}`, { waitUntil: 'commit' });
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
