import { Then } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { STUB_URLS } from '../support/stubs';

/**
 * ChatGPTの参照画像付き(images/edits)生成(issue #1602)のステップ定義。
 *
 * ジョブの要求・終了待ち・「done」の検証、参照画像の用意は `imageReferenceGeneration.steps.ts` /
 * `media.steps.ts` の既存ステップを共有する。ここにあるのは、ChatGPT スタブが記録した
 * `/images/edits`(multipart)・`/images/generations` の受信内容の検査と、失敗時の検証だけ。
 */

interface StubEdit {
  prompt: string | null;
  model: string | null;
  image: { filename: string | null; contentType: string | null; bytes: number } | null;
}

interface StubGeneration {
  prompt: string | null;
}

interface OpenAiStubState {
  edits: StubEdit[];
  generations: StubGeneration[];
}

async function openAiState(): Promise<OpenAiStubState> {
  const response = await fetch(`${STUB_URLS['openai-image']}/__control/state`);
  expect(response.ok, 'ChatGPT画像生成スタブの状態を取得できません').toBe(true);
  return (await response.json()) as OpenAiStubState;
}

function marker(ctx: Record<string, unknown>): string {
  const run = ctx.referenceRun as { marker: string } | undefined;
  expect(run, '参照画像付きの生成がまだ要求されていません').toBeDefined();
  return (run as { marker: string }).marker;
}

function finalOf(ctx: Record<string, unknown>): { status: string; resultPayload: string | null } {
  const job = ctx.imageJob as { final: { status: string; resultPayload: string | null } | null } | undefined;
  expect(job?.final, 'ジョブの終了を待っていません').toBeTruthy();
  return (job as { final: { status: string; resultPayload: string | null } }).final;
}

Then(
  /^ChatGPTスタブはその生成の参照画像と指示を、モデル「([^」]+)」でimages\/editsに受け取っている$/,
  async ({ ctx }, model: string) => {
    const mark = marker(ctx);
    const edit = (await openAiState()).edits.find((e) => e.prompt?.includes(mark));
    expect(edit, `ChatGPTスタブの /images/edits に印(${mark})を持つ要求が記録されていません`).toBeDefined();
    expect(edit?.model).toBe(model);
    expect(edit?.image, '参照画像(multipartのimage)が送られていません').not.toBeNull();
    expect(edit?.image?.filename, '参照画像にファイル名が付いていません').toBeTruthy();
    expect(edit?.image?.contentType).toBe('image/png');
    expect(edit?.image?.bytes).toBeGreaterThan(0);
  }
);

Then(/^ChatGPTスタブは、その生成をimages\/generationsでは受け取っていない$/, async ({ ctx }) => {
  const mark = marker(ctx);
  expect((await openAiState()).generations.some((g) => g.prompt?.includes(mark))).toBe(false);
});

Then(
  /^ChatGPTスタブは、その生成をimages\/generationsで受け取り、images\/editsでは受け取っていない$/,
  async ({ ctx }) => {
    const mark = marker(ctx);
    const state = await openAiState();
    expect(state.generations.some((g) => g.prompt?.includes(mark)), '/images/generations に届いていません').toBe(true);
    expect(state.edits.some((e) => e.prompt?.includes(mark)), '/images/edits へ送られてはいけません').toBe(false);
  }
);

Then(/^ChatGPTスタブは、その生成をimages\/generationsでもimages\/editsでも受け取っていない$/, async ({ ctx }) => {
  const mark = marker(ctx);
  const state = await openAiState();
  expect(state.generations.some((g) => g.prompt?.includes(mark))).toBe(false);
  expect(state.edits.some((e) => e.prompt?.includes(mark))).toBe(false);
});

Then('そのジョブは「failed」で終わり、理由にこのプロジェクトでAPIキーを設定するよう示される', async ({ ctx }) => {
  const final = finalOf(ctx);
  expect(final.status, `結果: ${final.resultPayload}`).toBe('failed');
  const result = JSON.parse(final.resultPayload ?? '{}') as { error?: string };
  expect(result.error).toContain('このプロジェクト');
  expect(result.error).toContain('APIキー');
});

Then(
  /^そのジョブは「failed」で終わり、理由に失敗した画像編集の呼び出しと状態コード「(\d+)」が示される$/,
  async ({ ctx }, status: string) => {
    const final = finalOf(ctx);
    expect(final.status, `結果: ${final.resultPayload}`).toBe('failed');
    const result = JSON.parse(final.resultPayload ?? '{}') as { error?: string };
    expect(result.error).toContain('ChatGPT');
    expect(result.error).toContain('images/edits');
    expect(result.error).toContain(String(status));
  }
);

Then('そのプロジェクトの生成画像は参照画像だけで、新しい画像は登録されていない', async ({ ctx, request }) => {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const response = await request.get(`/api/generated-images?projectId=${ctx.mediaProjectId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok(), `生成画像一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  const ids = ((await response.json()) as { id: number }[]).map((image) => image.id);
  expect(ids).toEqual([ctx.refGenReferenceImageId]);
});
