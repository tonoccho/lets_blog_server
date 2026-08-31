import { render, screen, fireEvent } from '@testing-library/react'
import { signOut } from 'next-auth/react'
import { LogoutButton } from '../LogoutButton'

jest.mock('next-auth/react')

describe('LogoutButton', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('renders logout button', () => {
    render(<LogoutButton />)
    const button = screen.getByRole('button', { name: /ログアウト/i })
    expect(button).toBeInTheDocument()
  })

  it('calls signOut with correct callback URL when clicked', () => {
    render(<LogoutButton />)
    const button = screen.getByRole('button', { name: /ログアウト/i })
    fireEvent.click(button)
    expect(signOut).toHaveBeenCalledWith({ callbackUrl: '/login' })
  })

  it('has correct styling classes', () => {
    render(<LogoutButton />)
    const button = screen.getByRole('button', { name: /ログアウト/i })
    expect(button).toHaveClass('text-sm', 'text-neutral-600', 'hover:text-neutral-900')
  })
})
