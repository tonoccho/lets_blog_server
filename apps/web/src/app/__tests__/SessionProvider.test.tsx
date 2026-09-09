/**
 * issue #1053: SessionProvider.tsx が next-auth の SessionProvider へ渡す
 * refetchInterval が tokenRefreshPolicy.ts の定数と揃っていること、children が
 * そのまま描画されることを検証する。
 */
import { render, screen } from '@testing-library/react'

const nextAuthSessionProviderMock = jest.fn()
jest.mock('next-auth/react', () => ({
  SessionProvider: (props: { refetchInterval?: number; session?: unknown; children?: React.ReactNode }) => {
    nextAuthSessionProviderMock(props)
    return props.children
  },
}))

import { SessionProvider } from '../SessionProvider'
import { ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS } from '@/lib/tokenRefreshPolicy'

describe('SessionProvider(issue #1053)', () => {
  it('tokenRefreshPolicy.tsのACCESS_TOKEN_REFETCH_INTERVAL_SECONDSをrefetchIntervalに渡す', () => {
    render(
      <SessionProvider session={null}>
        <p>子要素</p>
      </SessionProvider>
    )

    expect(screen.getByText('子要素')).toBeInTheDocument()
    expect(nextAuthSessionProviderMock).toHaveBeenCalledWith(
      expect.objectContaining({ refetchInterval: ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS, session: null })
    )
  })

  it('サーバー解決済みのsessionをそのまま渡す(issue #778)', () => {
    const session = { user: { id: 'u1', role: 'admin' }, expires: '2099-01-01' }
    render(
      <SessionProvider session={session as never}>
        <p>子要素2</p>
      </SessionProvider>
    )

    expect(nextAuthSessionProviderMock).toHaveBeenCalledWith(expect.objectContaining({ session }))
  })
})
