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

  describe('controlled mode', () => {
    it('shows the tab named by activeTabId and reports changes without switching itself', () => {
      const onTabChange = jest.fn()
      render(<Tabs tabs={mockTabs} activeTabId="tab2" onTabChange={onTabChange} />)
      expect(screen.getByText('Content 2')).toBeInTheDocument()
      fireEvent.click(screen.getByText('Tab 3'))
      expect(onTabChange).toHaveBeenCalledWith('tab3')
      expect(screen.getByText('Content 2')).toBeInTheDocument()
    })

    it('marks the active tab with aria-pressed in both modes', () => {
      const { unmount } = render(<Tabs tabs={mockTabs} />)
      expect(screen.getByText('Tab 1')).toHaveAttribute('aria-pressed', 'true')
      expect(screen.getByText('Tab 2')).toHaveAttribute('aria-pressed', 'false')
      unmount()
      render(<Tabs tabs={mockTabs} activeTabId="tab3" onTabChange={() => {}} />)
      expect(screen.getByText('Tab 3')).toHaveAttribute('aria-pressed', 'true')
    })

    it('uncontrolled mode still works when onTabChange is given without activeTabId', () => {
      const onTabChange = jest.fn()
      render(<Tabs tabs={mockTabs} onTabChange={onTabChange} />)
      fireEvent.click(screen.getByText('Tab 2'))
      expect(onTabChange).toHaveBeenCalledWith('tab2')
      expect(screen.getByText('Content 2')).toBeInTheDocument()
    })
  })

  describe('defaultTabId', () => {
    it('最初に選択されるタブを指定できる(その後は内部状態で切り替わる)', () => {
      render(<Tabs tabs={mockTabs} defaultTabId="tab3" />)
      expect(screen.getByText('Content 3')).toBeInTheDocument()
      fireEvent.click(screen.getByText('Tab 1'))
      expect(screen.getByText('Content 1')).toBeInTheDocument()
    })

    it('存在しないidなら先頭タブにする', () => {
      render(<Tabs tabs={mockTabs} defaultTabId="nope" />)
      expect(screen.getByText('Content 1')).toBeInTheDocument()
    })
  })

  it('defaultTabId が(同じ画面のまま)変わったら、選択中のタブがそれに追従する', () => {
    const { rerender } = render(<Tabs tabs={mockTabs} defaultTabId="tab1" />)
    expect(screen.getByText('Content 1')).toBeInTheDocument()

    rerender(<Tabs tabs={mockTabs} defaultTabId="tab3" />)
    expect(screen.getByText('Content 3')).toBeInTheDocument()
    expect(screen.queryByText('Content 1')).not.toBeInTheDocument()
  })

  it('defaultTabId が変わらない再描画では、利用者が選んだタブを保つ', () => {
    const { rerender } = render(<Tabs tabs={mockTabs} defaultTabId="tab1" />)
    fireEvent.click(screen.getByText('Tab 2'))

    rerender(<Tabs tabs={mockTabs} defaultTabId="tab1" />)
    expect(screen.getByText('Content 2')).toBeInTheDocument()
  })

  it('存在しない defaultTabId に変わっても、選択中のタブは変えない', () => {
    const { rerender } = render(<Tabs tabs={mockTabs} defaultTabId="tab2" />)

    rerender(<Tabs tabs={mockTabs} defaultTabId="nope" />)
    expect(screen.getByText('Content 2')).toBeInTheDocument()
  })
})
