import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { CheckConnectionButton } from '../CheckConnectionButton'

/**
 * 自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は差し替えない。
 * 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
 */
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
}))

import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime'
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime'

const CHECK = 'POST /api/sites/:id/test-connection'
/** 疎通確認 API(gateway の向こう)の代役。受け取ったサイトIDを記録する。 */
const checked: number[] = []
let respond: () => Promise<Response> | Response

const SUCCESS = {
  connectionCheckStatus: 'SUCCESS',
  hasAdminCapability: true,
  failureReason: null,
  detail: 'Connection successful',
}

describe('CheckConnectionButton', () => {
  beforeEach(() => {
    checked.length = 0
    resetServerRuntime(ADMIN_SESSION)
    respond = () => jsonResponse(SUCCESS)
    installFetch({
      [CHECK]: (_url, _init, [id]) => {
        checked.push(Number(id))
        return respond()
      },
    })
  })

  it('renders button with initial label', () => {
    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    expect(button).toBeInTheDocument()
  })

  it('calls checkSiteConnectionAction when clicked', async () => {
    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(checked).toEqual([1])
    })
  })

  it('shows success status when connection succeeds', async () => {
    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(screen.getByText('SUCCESS')).toBeInTheDocument()
    })
    expect(screen.getByText('Connection successful')).toBeInTheDocument()
  })

  it('shows failure status when connection fails', async () => {
    respond = () => textResponse('Connection failed', 500)

    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(screen.getByText('FAILED')).toBeInTheDocument()
    })
  })

  it('shows the failure reason when the check reports one', async () => {
    respond = () =>
      jsonResponse({ connectionCheckStatus: 'FAILED', hasAdminCapability: null, failureReason: 'SSH接続に失敗', detail: null })

    render(<CheckConnectionButton id={1} />)
    fireEvent.click(screen.getByText('疎通確認'))

    expect(await screen.findByText('FAILED')).toBeInTheDocument()
    expect(screen.getByText('SSH接続に失敗')).toBeInTheDocument()
  })

  it('shows loading state while checking', async () => {
    respond = () => new Promise((resolve) => setTimeout(() => resolve(jsonResponse(SUCCESS)), 100))

    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(screen.getByText('確認中…')).toBeInTheDocument()
    }, { timeout: 50 })
  })

  it('shows warning for missing admin capability', async () => {
    respond = () => jsonResponse({ ...SUCCESS, hasAdminCapability: false })

    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(screen.getByText(/このサイトの認証情報には管理者権限/)).toBeInTheDocument()
    })
  })

  it('passes correct ID to action', async () => {
    render(<CheckConnectionButton id={42} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(checked).toEqual([42])
    })
  })
})
