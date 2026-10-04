/**
 * #1589: app/error.tsx はルートレイアウトの <main> 内に描画されるセグメント境界であり、
 * <html>/<body> を返すとシェルの中に入れ子になって見出しが不可視になる。
 * 描画結果は html/body 要素を含まず、見出しとエラーIDを持つ。
 */
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

import RootError from '../error';

jest.mock('next/link', () => ({
  __esModule: true,
  default: ({ href, children }: { href: string; children: React.ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

describe('app/error.tsx の描画構造', () => {
  it('html / body 要素を入れ子で描画しない', () => {
    const html = renderToStaticMarkup(<RootError error={new Error('boom')} reset={() => {}} />);
    expect(html).not.toMatch(/<html[\s>]/);
    expect(html).not.toMatch(/<body[\s>]/);
    expect(html).toContain('エラーが発生しました');
  });

  it('digest があればエラーIDに表示する', () => {
    const error = Object.assign(new Error('boom'), { digest: 'abc123' });
    const html = renderToStaticMarkup(<RootError error={error} reset={() => {}} />);
    expect(html).toContain('abc123');
  });
});
