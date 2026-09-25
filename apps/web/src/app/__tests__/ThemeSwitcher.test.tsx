import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ThemeSwitcher } from '../ThemeSwitcher'

describe('ThemeSwitcher', () => {
  beforeEach(() => {
    localStorage.clear()
    jest.clearAllMocks()

    // Mock window.matchMedia
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: jest.fn().mockImplementation((query) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: jest.fn(),
        removeListener: jest.fn(),
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
        dispatchEvent: jest.fn(),
      })),
    })
  })

  it('renders theme switcher button', async () => {
    render(<ThemeSwitcher />)
    await waitFor(() => {
      const button = screen.getByRole('button')
      expect(button).toBeInTheDocument()
    })
  })

  it('loads and applies stored theme on mount', async () => {
    localStorage.setItem('theme', 'dark')
    render(<ThemeSwitcher />)
    await waitFor(() => {
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    })
  })

  it('applies auto theme by default, resolved against OS preference', async () => {
    render(<ThemeSwitcher />)
    await waitFor(() => {
      // matchMedia is mocked to matches: false (light), so auto resolves to light
      expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    })
  })

  it('cycles through themes when clicked', async () => {
    render(<ThemeSwitcher />)
    const button = await screen.findByRole('button')

    // First click: light
    fireEvent.click(button)
    await waitFor(() => {
      expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    })

    // Second click: dark
    fireEvent.click(button)
    await waitFor(() => {
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    })

    // Third click: auto (resolved against OS preference, mocked as light)
    fireEvent.click(button)
    await waitFor(() => {
      expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    })
  })

  it('persists theme to localStorage', async () => {
    render(<ThemeSwitcher />)
    const button = await screen.findByRole('button')
    fireEvent.click(button)
    await waitFor(() => {
      expect(localStorage.getItem('theme')).toBe('light')
    })
  })

  it('has correct aria-label', async () => {
    render(<ThemeSwitcher />)
    const button = await screen.findByRole('button')
    expect(button).toHaveAttribute('aria-label')
  })
})
