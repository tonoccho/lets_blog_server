import { render, screen } from '@testing-library/react'
import ProjectDetailLoading from '../(detail)/loading'

describe('プロジェクト詳細画面の loading UI(issue #1475)', () => {
  it('データ取得の完了を待たずに描画できる(props も非同期処理も要らない)', () => {
    render(<ProjectDetailLoading />)
    expect(screen.getByTestId('route-loading')).toBeInTheDocument()
  })

  it('一括管理の比較取得を待っている旨を利用者に伝える', () => {
    render(<ProjectDetailLoading />)
    expect(screen.getByRole('status')).toHaveTextContent('プロジェクトを読み込み中…')
  })
})
