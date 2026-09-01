import { DIAGRAM_REFERENCE_PATTERN } from '../diagramEditorPanel';

describe('DIAGRAM_REFERENCE_PATTERN', () => {
  it('ダイアグラムのMarkdown画像参照にマッチし、ファイル名とIDを抽出する', () => {
    const line = '![システム構成図](assets/diagram-42-1699999999999.svg)';
    const match = DIAGRAM_REFERENCE_PATTERN.exec(line);

    expect(match).not.toBeNull();
    expect(match?.[1]).toBe('diagram-42-1699999999999.svg');
    expect(match?.[2]).toBe('42');
  });

  it('通常の画像参照にはマッチしない', () => {
    const line = '![猫の写真](assets/gallery-1-1699999999999.png)';
    expect(DIAGRAM_REFERENCE_PATTERN.test(line)).toBe(false);
  });

  it('assets/配下でないSVG参照にはマッチしない', () => {
    const line = '![外部](https://example.com/diagram-1-1699999999999.svg)';
    expect(DIAGRAM_REFERENCE_PATTERN.test(line)).toBe(false);
  });

  it('ID部分のみのタイムスタンプ欠落はマッチしない', () => {
    const line = '![不正](assets/diagram-42.svg)';
    expect(DIAGRAM_REFERENCE_PATTERN.test(line)).toBe(false);
  });
});
