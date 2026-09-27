import { render, screen } from '@testing-library/react';
import { FetchErrorNotice } from '../FetchErrorNotice';

describe('FetchErrorNotice', () => {
  it('失敗したラベルがないときは何も描画しない', () => {
    const { container } = render(<FetchErrorNotice labels={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('失敗したラベルを role=alert で列挙し、再読み込みを促す', () => {
    render(<FetchErrorNotice labels={['サイト一覧', '投稿一覧']} />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('サイト一覧を取得できませんでした');
    expect(alert).toHaveTextContent('投稿一覧を取得できませんでした');
    expect(alert).toHaveTextContent('時間をおいて再読み込みしてください');
  });
});
