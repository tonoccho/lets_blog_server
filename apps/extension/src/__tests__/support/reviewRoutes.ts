/**
 * レビュー(5 ステップ)の外部通信の応答表(issue #1840)。投稿・プレビュー直前のレビューが呼ぶ API を
 * サーバー側の振る舞いとして用意する。
 */
import { Reply, jsonReply } from './localHttpServer';

const STEPS = ['JAPANESE', 'PROOFREADING', 'FACT_CHECK', 'READER_PERSPECTIVE', 'STYLE'];

export const NO_FINDINGS = { suggestions: [], skipped: false };

/** 本文中の `originalText` を指摘するステップの応答。 */
export function findingFor(step: string, originalText: string): unknown {
  return { suggestions: [{ stepKey: step, originalText, message: '直す', suggestion: null, sources: [] }], skipped: false };
}

/**
 * 各ステップの応答。`overrides` でステップごとに差し替える。
 * 値が Reply ならそのまま、そうでなければ JSON として返す。
 */
export function reviewRoutes(
  projectId: number,
  overrides: Record<string, unknown | Reply> = {}
): Record<string, Reply> {
  const table: Record<string, Reply> = {
    'GET /api/metadata/post-statuses': jsonReply([
      { value: 'draft', label: '下書き' },
      { value: 'publish', label: '公開' },
    ]),
  };
  for (const step of STEPS) {
    const override = overrides[step];
    const reply =
      override === undefined
        ? jsonReply(NO_FINDINGS)
        : typeof override === 'object' && override !== null && 'status' in override
          ? (override as Reply)
          : jsonReply(override);
    table[`POST /api/projects/${projectId}/ai/review-steps/${step}/suggestions`] = reply;
  }
  return table;
}
