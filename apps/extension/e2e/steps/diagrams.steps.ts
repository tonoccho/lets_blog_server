/** ダイアグラムのステップ(issue #942 / AT-16)。 */

import { Then, When } from '../support/gherkin';
import { w } from './common.steps';
import * as apiClient from '../../src/apiClient';

const XML = '<mxfile host="e2e"><diagram id="at16">AT16</diagram></mxfile>';
const SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10"/></svg>';

When('ダイアグラム {string} を作成する', async (world, name) => {
  const scope = w(world);
  (scope as { diagram?: unknown }).diagram = await apiClient.createDiagram(scope.token, scope.actor, {
    projectId: scope.project.id,
    name,
    xml: XML,
    svg: SVG,
  });
});

Then('作成したダイアグラムのSVGを取得できる', async (world) => {
  const scope = w(world);
  const diagram = (scope as unknown as { diagram: { id: number } }).diagram;
  const svg = await apiClient.getDiagramSvg(scope.token, scope.actor, diagram.id);
  if (!svg.includes('<svg')) throw new Error(`SVGが取得できませんでした: ${svg.slice(0, 120)}`);
});

When('作成したダイアグラムの名前を {string} に変更する', async (world, name) => {
  const scope = w(world);
  const diagram = (scope as unknown as { diagram: { id: number } }).diagram;
  (scope as { diagram?: unknown }).diagram = await apiClient.updateDiagram(
    scope.token,
    scope.actor,
    diagram.id,
    { name, xml: XML, svg: SVG },
    scope.project.id
  );
});

Then('ダイアグラム一覧に変更後の名前で表示される', async (world) => {
  const scope = w(world);
  const diagram = (scope as unknown as { diagram: { id: number; name: string } }).diagram;
  const list = await apiClient.listDiagrams(scope.token, scope.actor, scope.project.id);
  const found = list.find((d) => d.id === diagram.id);
  if (found?.name !== diagram.name) {
    throw new Error(`一覧の名前が一致しません: ${JSON.stringify(found)}`);
  }
});

When('作成したダイアグラムを削除する', async (world) => {
  const scope = w(world);
  const diagram = (scope as unknown as { diagram: { id: number } }).diagram;
  await apiClient.deleteDiagram(scope.token, scope.actor, diagram.id, scope.project.id);
});

Then('ダイアグラム一覧に表示されなくなる', async (world) => {
  const scope = w(world);
  const diagram = (scope as unknown as { diagram: { id: number } }).diagram;
  const list = await apiClient.listDiagrams(scope.token, scope.actor, scope.project.id);
  if (list.some((d) => d.id === diagram.id)) {
    throw new Error(`削除したダイアグラムが一覧に残っています: ${diagram.id}`);
  }
});
