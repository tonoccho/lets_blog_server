import { appendFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import type {
  FullConfig,
  FullResult,
  Reporter,
  Suite,
  TestCase,
  TestResult,
} from '@playwright/test/reporter';

/**
 * 受け入れテストの実行を、あとから集計できる形で追記する Reporter。
 *
 * これが無かったとき、無人開発ループの所要時間の内訳は
 * `queue-metrics-json.py` の `classify()`(コマンド文字列の正規表現)だけが頼りで、
 * 受け入れテストの実行時間を実際より少なく見積もっていた。#1053 の実装では
 * Playwright を背後で起動して
 *
 *   until grep -qE "passed|failed" "$f"; do sleep 5; done
 *
 * とログを待つ形が使われ、コマンド文字列に playwright も test:at も現れないため、
 * 45分ぶんの待ちが acceptance-test ではなく shell に分類されていた。
 * 実行そのものに記録させれば、呼び出し方に関係なく正しい時間が残る。
 *
 * 出力は JSONL(1行1レコード、追記のみ)。既定の書き出し先は
 * `~/.local/state/claude-auto/at-runs.jsonl` で、`AT_METRICS_LOG` で変えられる。
 * レポーターの失敗がテスト結果に影響してはならないので、全て握りつぶす。
 *
 * レコードは2種類:
 *   {"kind":"test", ...} テスト1件ぶん。どのシナリオが遅いかを見るため。
 *   {"kind":"run",  ...} `playwright test` 1回ぶん。集計はこちらを使う。
 */

const DEFAULT_LOG = join(homedir(), '.local/state/claude-auto/at-runs.jsonl');

/** 実行を Issue に結びつけるための手掛かり。ブランチ名に Issue 番号が入る規約に乗る。 */
function gitInfo(): { branch: string | null; issue: string | null } {
  try {
    const branch = execFileSync('git', ['branch', '--show-current'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
    const m = branch.match(/(\d{2,})/);
    return { branch: branch || null, issue: m ? m[1] : null };
  } catch {
    return { branch: null, issue: null };
  }
}

export default class AtMetricsReporter implements Reporter {
  private readonly logPath = process.env.AT_METRICS_LOG || DEFAULT_LOG;
  private readonly runId = `${new Date().toISOString().replace(/[-:.]/g, '').slice(0, 15)}-${process.pid}`;
  private startedAt = new Date();
  private projects: string[] = [];
  private counts: Record<string, number> = {};
  private testMs = 0;

  private write(rec: Record<string, unknown>): void {
    try {
      mkdirSync(dirname(this.logPath), { recursive: true });
      appendFileSync(this.logPath, JSON.stringify(rec) + '\n');
    } catch {
      /* 記録できなくてもテストは続ける */
    }
  }

  onBegin(config: FullConfig, suite: Suite): void {
    this.startedAt = new Date();
    // config.projects は設定上の全プロジェクト。--project で絞った実行でも12個並び、
    // どの段階が走ったのか分からなくなる。root suite の子が実際に選ばれた
    // プロジェクトのスイートなので、そちらから取る。
    this.projects = suite.suites.map((s) => s.title).filter(Boolean);
    this.write({
      kind: 'begin',
      run: this.runId,
      ts: this.startedAt.toISOString(),
      projects: this.projects,
      tests: suite.allTests().length,
      workers: config.workers,
      grep: String(config.grep ?? ''),
      ...gitInfo(),
    });
  }

  onTestEnd(test: TestCase, result: TestResult): void {
    this.counts[result.status] = (this.counts[result.status] ?? 0) + 1;
    this.testMs += result.duration;
    this.write({
      kind: 'test',
      run: this.runId,
      ts: new Date().toISOString(),
      project: test.parent.project()?.name ?? null,
      file: test.location.file.replace(`${process.cwd()}/`, ''),
      title: test.titlePath().filter(Boolean).slice(2).join(' > '),
      status: result.status,
      durationMs: result.duration,
      retry: result.retry,
      errors: result.errors.length,
    });
  }

  onEnd(result: FullResult): void {
    const endedAt = new Date();
    this.write({
      kind: 'run',
      run: this.runId,
      startedAt: this.startedAt.toISOString(),
      endedAt: endedAt.toISOString(),
      // 集計はこの durationMs を使う。テスト時間の合計(testMs)は並列実行のぶん
      // 壁時計より長くなりうるので、別のキーで併記するだけにする。
      durationMs: endedAt.getTime() - this.startedAt.getTime(),
      testMs: this.testMs,
      status: result.status,
      projects: this.projects,
      counts: this.counts,
      ...gitInfo(),
    });
  }
}
