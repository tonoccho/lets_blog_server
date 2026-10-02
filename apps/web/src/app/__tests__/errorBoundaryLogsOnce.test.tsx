/**
 * #1394: エラーバウンダリ5ファイルは、StrictModeのeffect二重実行下でも
 * 1件のエラーにつき /client-errors へのPOSTを1件だけ送る。
 * 別のエラーに変わったときは改めて送る。
 */
import React from 'react';
import { render } from '@testing-library/react';

import GlobalError from '../error';
import SitesError from '../sites/error';
import PostsError from '../posts/error';
import UsersError from '../users/error';
import ProjectsError from '../projects/error';

jest.mock('next/link', () => ({
  __esModule: true,
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

type Boundary = (props: { error: Error & { digest?: string }; reset: () => void }) => React.ReactElement;

const boundaries: Array<[string, Boundary]> = [
  ['app/error.tsx', GlobalError],
  ['sites/error.tsx', SitesError],
  ['posts/error.tsx', PostsError],
  ['users/error.tsx', UsersError],
  ['projects/error.tsx', ProjectsError],
];

function clientErrorPosts(fetchMock: jest.Mock) {
  return fetchMock.mock.calls.filter(
    ([url, init]) => url === '/client-errors' && init?.method === 'POST',
  );
}

describe.each(boundaries)('%s のクライアントエラー送信', (_name, Boundary) => {
  let fetchMock: jest.Mock;
  let consoleSpy: jest.SpyInstance[];

  beforeEach(() => {
    fetchMock = jest.fn().mockResolvedValue({ ok: true });
    global.fetch = fetchMock as unknown as typeof fetch;
    consoleSpy = [
      jest.spyOn(console, 'error').mockImplementation(() => {}),
      jest.spyOn(console, 'warn').mockImplementation(() => {}),
    ];
  });

  afterEach(() => {
    consoleSpy.forEach((s) => s.mockRestore());
  });

  it('StrictMode下でも1件のエラーにつきPOSTは1件', () => {
    render(
      <React.StrictMode>
        <Boundary error={new Error('boom')} reset={() => {}} />
      </React.StrictMode>,
    );
    expect(clientErrorPosts(fetchMock)).toHaveLength(1);
  });

  it('StrictModeなしでも1件', () => {
    render(<Boundary error={new Error('boom')} reset={() => {}} />);
    expect(clientErrorPosts(fetchMock)).toHaveLength(1);
  });

  it('同じerrorでの再レンダーでは追加送信しない', () => {
    const error = new Error('same');
    const { rerender } = render(
      <React.StrictMode>
        <Boundary error={error} reset={() => {}} />
      </React.StrictMode>,
    );
    rerender(
      <React.StrictMode>
        <Boundary error={error} reset={() => {}} />
      </React.StrictMode>,
    );
    expect(clientErrorPosts(fetchMock)).toHaveLength(1);
  });

  it('別のerrorに変わったときは改めて送る', () => {
    const { rerender } = render(
      <React.StrictMode>
        <Boundary error={new Error('first')} reset={() => {}} />
      </React.StrictMode>,
    );
    rerender(
      <React.StrictMode>
        <Boundary error={new Error('second')} reset={() => {}} />
      </React.StrictMode>,
    );
    const posts = clientErrorPosts(fetchMock);
    expect(posts).toHaveLength(2);
    expect(JSON.parse(posts[1][1].body).message).toBe('second');
  });
});
