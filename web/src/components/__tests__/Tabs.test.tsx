import { render, screen, fireEvent } from '@testing-library/react'
import { Tabs, type TabItem } from '../Tabs'

describe('Tabs', () => {
  const mockTabs: TabItem[] = [
    { id: 'tab1', label: 'Tab 1', content: <div>Content 1</div> },
    { id: 'tab2', label: 'Tab 2', content: <div>Content 2</div> },
    { id: 'tab3', label: 'Tab 3', content: <div>Content 3</div> },
  ]

  it('renders all tabs', () => {
    render(<Tabs tabs={mockTabs} />)
    expect(screen.getByText('Tab 1')).toBeInTheDocument()
    expect(screen.getByText('Tab 2')).toBeInTheDocument()
    expect(screen.getByText('Tab 3')).toBeInTheDocument()
  })

  it('shows first tab content by default', () => {
    render(<Tabs tabs={mockTabs} />)
    expect(screen.getByText('Content 1')).toBeInTheDocument()
    expect(screen.queryByText('Content 2')).not.toBeInTheDocument()
  })

  it('switches tab content on click', () => {
    render(<Tabs tabs={mockTabs} />)
    const tab2Button = screen.getByText('Tab 2')
    fireEvent.click(tab2Button)
    expect(screen.getByText('Content 2')).toBeInTheDocument()
    expect(screen.queryByText('Content 1')).not.toBeInTheDocument()
  })

  it('applies active styling to selected tab', () => {
    render(<Tabs tabs={mockTabs} />)
    const tab1Button = screen.getByText('Tab 1')
    const tab2Button = screen.getByText('Tab 2')

    expect(tab1Button).toHaveClass('border-neutral-900', 'text-neutral-900')

    fireEvent.click(tab2Button)
    expect(tab2Button).toHaveClass('border-neutral-900', 'text-neutral-900')
    expect(tab1Button).toHaveClass('border-transparent', 'text-neutral-600')
  })

  it('handles multiple tab switches', () => {
    render(<Tabs tabs={mockTabs} />)

    fireEvent.click(screen.getByText('Tab 2'))
    expect(screen.getByText('Content 2')).toBeInTheDocument()

    fireEvent.click(screen.getByText('Tab 3'))
    expect(screen.getByText('Content 3')).toBeInTheDocument()

    fireEvent.click(screen.getByText('Tab 1'))
    expect(screen.getByText('Content 1')).toBeInTheDocument()
  })

  it('handles single tab', () => {
    const singleTab: TabItem[] = [
      { id: 'single', label: 'Only Tab', content: <div>Only Content</div> },
    ]
    render(<Tabs tabs={singleTab} />)
    expect(screen.getByText('Only Content')).toBeInTheDocument()
  })

  it('handles empty tabs array', () => {
    render(<Tabs tabs={[]} />)
    const buttons = screen.queryAllByRole('button')
    expect(buttons).toHaveLength(0)
  })
})
