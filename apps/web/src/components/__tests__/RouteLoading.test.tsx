import { render, screen } from '@testing-library/react'
import { RouteLoading } from '../RouteLoading'

describe('RouteLoading', () => {
  it('announces a busy status region that acceptance tests can locate', () => {
    render(<RouteLoading />)
    const region = screen.getByRole('status')
    expect(region).toHaveAttribute('aria-busy', 'true')
    expect(region).toHaveAttribute('data-testid', 'route-loading')
  })

  it('uses the same default label as the client-side TabLoading', () => {
    render(<RouteLoading />)
    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
  })

  it('shows a custom label when given', () => {
    render(<RouteLoading label="プロジェクトを読み込み中…" />)
    expect(screen.getByText('プロジェクトを読み込み中…')).toBeInTheDocument()
    expect(screen.queryByText('読み込み中…')).not.toBeInTheDocument()
  })
})
