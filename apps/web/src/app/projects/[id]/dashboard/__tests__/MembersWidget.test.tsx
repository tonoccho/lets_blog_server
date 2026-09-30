import { render, screen, within } from '@testing-library/react';
import { MembersWidget } from '../MembersWidget';

const member = (userId: number, displayName: string | null, wpRole: string, email: string | null = null) => ({
  userId,
  displayName,
  wpRole,
  email,
});

describe('MembersWidget(issue #1502)', () => {
  it('メンバーの表示名とロールを一覧する', () => {
    render(
      <MembersWidget
        projectId={7}
        members={[member(1, '山田太郎', 'administrator'), member(2, '佐藤花子', 'author')]}
      />,
    );

    expect(screen.getByRole('heading', { name: 'メンバー' })).toBeInTheDocument();
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('山田太郎');
    expect(items[0]).toHaveTextContent('administrator');
    expect(items[1]).toHaveTextContent('佐藤花子');
    expect(items[1]).toHaveTextContent('author');
    expect(screen.getByText('2人')).toBeInTheDocument();
  });

  it('表示名がなければメールアドレス、それもなければユーザーIDで示す', () => {
    render(
      <MembersWidget
        projectId={7}
        members={[member(1, null, 'editor', 'a@example.com'), member(9, null, 'editor')]}
      />,
    );

    const items = screen.getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('a@example.com');
    expect(items[1]).toHaveTextContent('ユーザー#9');
  });

  it('メンバーが0人のとき「0人」と明示する', () => {
    render(<MembersWidget projectId={7} members={[]} />);

    expect(screen.getByText('0人')).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('取得に失敗したときは「0人」ではなく取得失敗を示す', () => {
    render(<MembersWidget projectId={7} members={[]} fetchFailed />);

    expect(screen.getByRole('alert')).toHaveTextContent('メンバーを取得できませんでした');
    expect(screen.queryByText('0人')).not.toBeInTheDocument();
  });

  it('詳細ページの「メンバー」タブへのリンクを置く', () => {
    render(<MembersWidget projectId={7} members={[]} />);

    expect(screen.getByRole('link', { name: 'メンバーを管理' })).toHaveAttribute('href', '/projects/7?tab=members');
  });

  it('取得失敗でも管理へのリンクは残る', () => {
    render(<MembersWidget projectId={7} members={[]} fetchFailed />);

    expect(screen.getByRole('link', { name: 'メンバーを管理' })).toHaveAttribute('href', '/projects/7?tab=members');
  });

  it('閲覧専用: ロール変更・削除・追加の操作部品を持たない', () => {
    const { container } = render(<MembersWidget projectId={7} members={[member(1, '山田太郎', 'editor')]} />);

    expect(within(container).queryByRole('combobox')).not.toBeInTheDocument();
    expect(within(container).queryByRole('button')).not.toBeInTheDocument();
    expect(container.querySelector('select, input, form')).toBeNull();
  });
});
