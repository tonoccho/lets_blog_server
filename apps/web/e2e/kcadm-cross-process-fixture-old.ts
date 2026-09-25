/**
 * issue #1328 AC2フィクスチャ(「対策前」): 共有モジュール化より前に9ファイルが複製していた
 * kcadm実装(ホスト側の直列化も再試行も無い。`avatarUpload.steps.ts`等と同型)を複写した
 * ものを、複数プロセスから同時に呼んで実際に"Failed to get lock"が起きることを示すための
 * 子プロセス側エントリ。`kcadm.test.ts`がesbuildでバンドルして実際に複数のNodeプロセスとして
 * 同時起動する(`token-cross-process-fixture.ts`と同じ手法)。
 *
 * `docker`はテスト側が用意する偽のスクリプト(コンテナ内`kcadm.config`のファイルロック競合を
 * 模す)に差し替えたPATH環境で動かすため、実際のdocker/Keycloakには依存しない。
 *
 * 全プロセスがほぼ同時に`docker exec`を呼ぶよう、readyファイルを書いてからgoファイルの
 * 出現をビジーウェイトで待つ簡易バリアを使う(`main.ts`ではなくこのファイル自身がバリアを
 * 実装する。テストのオーケストレーションと対称)。
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const KEYCLOAK_CONTAINER = 'lbs-keycloak';
const KCADM_BIN = '/opt/keycloak/bin/kcadm.sh';

/** 共有化(#1328)より前の複製実装そのもの。ホスト側の直列化も再試行も無い。 */
function kcadmOld(args: string[]): string {
  return execFileSync('docker', ['exec', KEYCLOAK_CONTAINER, KCADM_BIN, ...args], {
    encoding: 'utf-8',
    timeout: 30_000,
  });
}

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
    const output = kcadmOld(['get', 'users', '-r', 'letsblog', '-q', 'email=fixture@example.com']);
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
