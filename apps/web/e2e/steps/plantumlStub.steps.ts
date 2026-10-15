import { Given, Then, When } from './fixtures';
import { expect } from '../support';
import { encodePlantUml } from '../support/plantuml';
import { PLANTUML_STUB_URL, requirePlantUmlStub } from '../support/stubs';

/**
 * PlantUML のスタブ(issue #1802)の受け入れテスト。スタブを直接叩く。
 *
 * 符号化は `support/plantuml.ts`(services/media の PlantUmlEncoder.java と同じ)。
 * media-service を通さず、スタブ自身の約束(固定の PNG・復号と記録・決定的な 400 / 414・注入)だけを見る。
 */

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

interface Call {
  status: number;
  contentType: string;
  errorHeader: string | null;
  body: Buffer;
}

let calls: Call[] = [];

async function getPath(path: string): Promise<Call> {
  const res = await fetch(`${PLANTUML_STUB_URL}${path}`);
  const call: Call = {
    status: res.status,
    contentType: res.headers.get('content-type') ?? '',
    errorHeader: res.headers.get('x-plantuml-diagram-error'),
    body: Buffer.from(await res.arrayBuffer()),
  };
  calls.push(call);
  return call;
}

function lastCall(): Call {
  expect(calls.length, 'PlantUMLスタブを呼んでいない').toBeGreaterThan(0);
  return calls[calls.length - 1];
}

interface StubState {
  lastSource: string | null;
  sources: string[];
}

async function stubState(): Promise<StubState> {
  return (await (await fetch(`${PLANTUML_STUB_URL}/__control/state`)).json()) as StubState;
}

/** `.feature` に書いた `\n` は文字どおりの2文字なので、改行へ戻してから送る。 */
const unescapeNewlines = (text: string): string => text.replace(/\\n/g, '\n');

Given('PlantUMLスタブが起動していて初期状態である', async () => {
  await requirePlantUmlStub();
  const res = await fetch(`${PLANTUML_STUB_URL}/__control/reset`, { method: 'POST' });
  expect(res.ok, 'PlantUMLスタブのリセットに失敗した').toBe(true);
  calls = [];
});

When(/^PlantUMLスタブへソース「(.+)」を渡す$/, async ({}, source: string) => {
  await getPath(`/png/${encodePlantUml(unescapeNewlines(source))}`);
});

Given(/^PlantUMLスタブへソース「(.+)」を渡している$/, async ({}, source: string) => {
  await getPath(`/png/${encodePlantUml(unescapeNewlines(source))}`);
});

When(/^PlantUMLスタブへ符号化されていないパス「(.+)」を渡す$/, async ({}, path: string) => {
  await getPath(`/png/${path}`);
});

When(/^PlantUMLスタブへ (\d+) 文字のパスを渡す$/, async ({}, length: string) => {
  await getPath(`/png/${'A'.repeat(Number(length))}`);
});

Given(/^PlantUMLスタブへ次の1回を (\d+) にする指示を出す$/, async ({}, status: string) => {
  const res = await fetch(`${PLANTUML_STUB_URL}/__control/force`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: Number(status), count: 1 }),
  });
  expect(res.ok, 'PlantUMLスタブへのエラー注入に失敗した').toBe(true);
});

When('PlantUMLスタブをリセットする', async () => {
  const res = await fetch(`${PLANTUML_STUB_URL}/__control/reset`, { method: 'POST' });
  expect(res.ok).toBe(true);
});

Then(/^どちらの応答もステータス 200 の image\/png で、同じバイト列である$/, async () => {
  expect(calls).toHaveLength(2);
  for (const call of calls) {
    expect(call.status).toBe(200);
    expect(call.contentType).toContain('image/png');
  }
  expect(calls[1].body.equals(calls[0].body), '入力が違うのに画像が違う(固定ではない)').toBe(true);
});

Then('応答は PNG のシグネチャで始まる', async () => {
  expect(lastCall().body.subarray(0, 8).equals(PNG_SIGNATURE)).toBe(true);
});

Then(/^PlantUMLスタブの状態の直近のソースは「(.+)」である$/, async ({}, source: string) => {
  expect((await stubState()).lastSource).toBe(unescapeNewlines(source));
});

Then(/^PlantUMLスタブの状態の記録は (\d+) 件である$/, async ({}, n: string) => {
  expect((await stubState()).sources).toHaveLength(Number(n));
});

Then(/^PlantUMLスタブの応答のステータスは (\d+) である$/, async ({}, status: string) => {
  expect(lastCall().status).toBe(Number(status));
});

Then('PlantUMLスタブの応答は X-PlantUML-Diagram-Error ヘッダを持つ', async () => {
  const header = lastCall().errorHeader;
  expect(header, 'X-PlantUML-Diagram-Error が無い').not.toBeNull();
  expect(header!.length).toBeGreaterThan(0);
});
