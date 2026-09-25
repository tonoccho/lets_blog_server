import { render, screen, fireEvent } from '@testing-library/react'
import { signOut } from 'next-auth/react'
import { LogoutButton } from '../LogoutButton'
import { I18nProvider } from '../I18nProvider'

jest.mock('next-auth/react')

function renderWithI18n(ui: React.ReactElement) {
  return render(<I18nProvider>{ui}</I18nProvider>)
}

describe('LogoutButton', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('renders logout button', () => {
    renderWithI18n(<LogoutButton />)
    const button = screen.getByRole('button', { name: /ログアウト/i })
    expect(button).toBeInTheDocument()
  })

  it('calls signOut with correct callback URL when clicked', () => {
    renderWithI18n(<LogoutButton />)
    const button = screen.getByRole('button', { name: /ログアウト/i })
    fireEvent.click(button)
    expect(signOut).toHaveBeenCalledWith({ callbackUrl: '/login' })
  })

  it('has correct styling classes', () => {
    renderWithI18n(<LogoutButton />)
    const button = screen.getByRole('button', { name: /ログアウト/i })
    expect(button).toHaveClass('text-sm', 'text-neutral-900', 'hover:text-neutral-700')
  })
})
