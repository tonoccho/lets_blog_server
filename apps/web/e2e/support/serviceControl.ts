/**
 * シナリオの途中でサービスを停止・復旧させるための共通ヘルパー。
 *
 * 出所は `steps/degradation.steps.ts`(issue #943 / AT-17)。同じ仕組みを
 * `steps/diagram.steps.ts`(issue #937 / AT-11、Penpot 未起動時のふるまい)でも
 * 使うため、**片方に閉じた実装を複製せず**ここへ出した。
 *
 * ## 復旧は誰がするのか
 *
 * 停止したサービスは `ctx.stoppedServices` に積む。積んだものを起動し直して healthy を
 * 待つのは `steps/degradation.steps.ts` の `After({ tags: '@destructive' })` である。
 * サービスを止めるシナリオは必ず `@destructive`(docs/ACCEPTANCE_TESTING.md §10)なので、
 * どの `.feature` から止めてもこのフックが後始末する。
 *
 * 後始末のフックを**この共通モジュールへは移していない**。AT-17 のフックは
 * 「サービスを起動し直してから、そのシナリオが作ったサイトを削除する」順序に依存しており
 * (サイト削除は content-service が上がっていないと失敗する)、フックを2つに割ると
 * その順序が保証できなくなるためである。
 */
import { composeServiceControl, waitForServicesHealthy } from '../helpers';

/**
 * サービスの停止・起動・healthy待ちは分単位で時間がかかる。Playwright の既定タイムアウト
 * (30秒)のままでは、検証したい状態に辿り着く前にシナリオが打ち切られる。
 * 該当のステップだけを延長する(設定ファイルの既定値を動かすと、無関係なシナリオの
 * 打ち切り時間まで変わってしまう)。
 */
export const SERVICE_CONTROL_TIMEOUT_MS = 300_000;

/** 停止したサービスを覚えておき、シナリオの後始末で復旧させる。 */
export function markStopped(ctx: Record<string, unknown>, service: string): void {
  const stopped = (ctx.stoppedServices as string[] | undefined) ?? [];
  ctx.stoppedServices = [...stopped, service];
}

export function stopService(ctx: Record<string, unknown>, service: string): void {
  composeServiceControl('stop', service);
  markStopped(ctx, service);
}

export function startService(ctx: Record<string, unknown>, service: string): void {
  composeServiceControl('start', service);
  waitForServicesHealthy([service], 180);
  ctx.stoppedServices = ((ctx.stoppedServices as string[] | undefined) ?? [])
    .filter((stopped) => stopped !== service);
}
