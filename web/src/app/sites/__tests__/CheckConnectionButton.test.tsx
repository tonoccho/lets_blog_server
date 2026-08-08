import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { CheckConnectionButton } from '../CheckConnectionButton'
import * as actions from '../actions'

jest.mock('../actions', () => ({
  checkSiteConnectionAction: jest.fn(),
}))

describe('CheckConnectionButton', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('renders button with initial label', () => {
    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    expect(button).toBeInTheDocument()
  })

  it('calls checkSiteConnectionAction when clicked', async () => {
    ;(actions.checkSiteConnectionAction as jest.Mock).mockResolvedValue({
      connectionCheckStatus: 'SUCCESS',
      hasAdminCapability: true,
      failureReason: null,
      detail: 'Connection successful',
    })

    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(actions.checkSiteConnectionAction).toHaveBeenCalledWith(1)
    })
  })

  it('shows success status when connection succeeds', async () => {
    ;(actions.checkSiteConnectionAction as jest.Mock).mockResolvedValue({
      connectionCheckStatus: 'SUCCESS',
      hasAdminCapability: true,
      failureReason: null,
      detail: 'Connection successful',
    })

    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(screen.getByText('SUCCESS')).toBeInTheDocument()
    })
  })

  it('shows failure status when connection fails', async () => {
    ;(actions.checkSiteConnectionAction as jest.Mock).mockRejectedValue(new Error('Connection failed'))

    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(screen.getByText('FAILED')).toBeInTheDocument()
    })
  })

  it('shows loading state while checking', async () => {
    ;(actions.checkSiteConnectionAction as jest.Mock).mockImplementation(
      () => new Promise((resolve) => setTimeout(() => resolve({
        connectionCheckStatus: 'SUCCESS',
        hasAdminCapability: true,
        failureReason: null,
        detail: 'Connection successful',
      }), 100))
    )

    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(screen.getByText('確認中…')).toBeInTheDocument()
    }, { timeout: 50 })
  })

  it('shows warning for missing admin capability', async () => {
    ;(actions.checkSiteConnectionAction as jest.Mock).mockResolvedValue({
      connectionCheckStatus: 'SUCCESS',
      hasAdminCapability: false,
      failureReason: null,
      detail: 'Connection successful',
    })

    render(<CheckConnectionButton id={1} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(screen.getByText(/このサイトの認証情報には管理者権限/)).toBeInTheDocument()
    })
  })

  it('passes correct ID to action', async () => {
    ;(actions.checkSiteConnectionAction as jest.Mock).mockResolvedValue({
      connectionCheckStatus: 'SUCCESS',
      hasAdminCapability: true,
      failureReason: null,
      detail: 'Connection successful',
    })

    render(<CheckConnectionButton id={42} />)
    const button = screen.getByText('疎通確認')
    fireEvent.click(button)

    await waitFor(() => {
      expect(actions.checkSiteConnectionAction).toHaveBeenCalledWith(42)
    })
  })
})
