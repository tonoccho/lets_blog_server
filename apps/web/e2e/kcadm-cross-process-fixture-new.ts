/**
 * issue #1328 AC2フィクスチャ(「対策後」): 共有モジュール`./kcadm`(ホスト側直列化 + 保険の
 * 再試行)を複数プロセスから同時に呼んでも、コンテナ内`kcadm.config`のロック競合
 * ("Failed to get lock")が起きないことを示すための子プロセス側エントリ。
 * `kcadm-cross-process-fixture-old.ts`と対になる(同じバリア・同じ偽dockerを使う)。
 */
import fs from 'node:fs';
import path from 'node:path';
import { kcadm } from './kcadm';

function busyWaitForFile(filePath: string, timeoutMs: number): void {
  const started = Date.now();
  while (!fs.existsSync(filePath)) {
    if (Date.now() - started > timeoutMs) {
      throw new Error(`バリアファイル(${filePath})が${timeoutMs}ms以内に現れませんでした`);
    }
  }
}

function main(): void {
  const coordDir = process.argv[2];
  const index = process.argv[3];
  fs.writeFileSync(path.join(coordDir, `ready-${index}`), '');
  busyWaitForFile(path.join(coordDir, 'go'), 10_000);
  try {
    const output = kcadm(['get', 'users', '-r', 'letsblog', '-q', 'email=fixture@example.com']);
    process.stdout.write(output);
  } catch (error) {
    const message =
      `${(error as { stdout?: string }).stdout ?? ''}${(error as { stderr?: string }).stderr ?? ''}` ||
      String(error);
    process.stderr.write(message);
    process.exit(1);
  }
}

main();
