import { After } from './fixtures';
import { CLEANUPS_KEY } from '../support/responseBudgetFixtures';

/**
 * Server Action の3秒予算シナリオ(issue #1477)が `registerCleanup` で積んだ後片付けを流す。
 * 1つが失敗しても残りを流す(後片付けの失敗でほかの片付けを取り残さない)。
 */
After({ tags: '@response-budget' }, async ({ ctx }) => {
  const cleanups = (ctx[CLEANUPS_KEY] as Array<() => Promise<void>> | undefined) ?? [];
  const failures: unknown[] = [];
  for (const cleanup of [...cleanups].reverse()) {
    try {
      await cleanup();
    } catch (err) {
      failures.push(err);
    }
  }
  ctx[CLEANUPS_KEY] = [];
  if (failures.length > 0) {
    console.warn(`応答時間予算シナリオの後片付けに失敗しました(${failures.length}件): ${String(failures[0])}`);
  }
});
