/**
 * `ProjectAssetGenerationPanel` のテストが共有する足場(#1837)。
 *
 * 差し替えるのは `global.fetch`(外部との通信)だけ。自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は実物を通す。
 * `backend.*` の `jest.fn()` は gateway の向こうのバックエンドの代役で、HTTP の経路ごとに 1 つ。解決値は 200 の JSON、
 * `Error` の拒否は 500 の応答、`Error` でない値の拒否は通信そのものの失敗になる(`answer()`)。
 * 各代役には、実際に送られた HTTP 要求の本文(または URL のパラメータ)が引数として渡る。
 */
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { answer, installBackend } from "./projectCoreRuntime";

export const backend = {
  /** GET /api/ai/image-options?projectId=… */
  imageOptions: jest.fn(),
  /** GET /api/generated-images */
  gallery: jest.fn(),
  /** POST /api/projects/:projectId/ai/generate-image-prompt(引数: 要求の本文) */
  chatPrompt: jest.fn(),
  /** POST /api/ai/image/jobs(引数: 要求の本文。projectId を含む) */
  imageJob: jest.fn(),
  /** GET /api/generation-jobs/:id(引数: ジョブのID) */
  job: jest.fn(),
  /** POST /api/projects/:projectId/asset-images/:id/upload/jobs(引数: 生成画像のID) */
  assetUpload: jest.fn(),
  /** POST /api/generated-images/upload?projectId=1(引数: 送られたファイル) */
  imageUpload: jest.fn(),
};

const bodyOf = (init: RequestInit): unknown => (typeof init.body === "string" ? JSON.parse(init.body) : init.body);

/** 要求の本文を代役の第 1 引数に渡す。 */
const withBody =
  (fn: jest.Mock) =>
  (url: string, init: RequestInit, params: string[]) =>
    answer(() => fn(bodyOf(init)))(url, init, params);

/** URL の最後のパラメータ(`:id`)を数値にして代役の第 1 引数に渡す。 */
const withId =
  (fn: jest.Mock) =>
  (url: string, init: RequestInit, params: string[]) =>
    answer(() => fn(Number(params[params.length - 1])))(url, init, params);

export function installAssetPanelBackend(): jest.Mock {
  resetServerRuntime(ADMIN_SESSION);
  for (const double of Object.values(backend)) double.mockReset();
  return installBackend({
    "GET /api/ai/image-options": answer(() => backend.imageOptions()),
    "GET /api/generated-images": answer(() => backend.gallery()),
    "POST /api/projects/:projectId/ai/generate-image-prompt": withBody(backend.chatPrompt),
    "POST /api/ai/image/jobs": withBody(backend.imageJob),
    "GET /api/generation-jobs/:id": withId(backend.job),
    "POST /api/projects/:projectId/asset-images/:id/upload/jobs": withId(backend.assetUpload),
    "POST /api/generated-images/upload": (url, init, params) =>
      answer(() => backend.imageUpload((init.body as FormData).get("file")))(url, init, params),
  });
}

/** 完了した画像生成ジョブ(`resultPayload.imageIds` に生成された画像のID)。 */
export function imageJobDone(ids: number[], id = 9) {
  return {
    id,
    type: "image_generation",
    status: "done",
    requestPayload: null,
    resultPayload: JSON.stringify({ imageIds: ids }),
    createdAt: "",
    updatedAt: "",
  };
}
