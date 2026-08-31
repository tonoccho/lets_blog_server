import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { DiagramGalleryGrid } from '../DiagramGalleryGrid'
import * as actions from '../actions'
import type { DiagramSummary } from '@/lib/apiClient'

jest.mock('../actions', () => ({
  getDiagramAction: jest.fn(),
  deleteDiagramAction: jest.fn(),
}))

const DIAGRAM: DiagramSummary = {
  id: 1,
  projectId: 2,
  name: 'シーケンス図',
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-02T00:00:00Z',
}

describe('DiagramGalleryGrid', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.deleteDiagramAction as jest.Mock).mockResolvedValue(undefined)
  })

  it('一覧にダイアグラム名を表示する', () => {
    render(<DiagramGalleryGrid diagrams={[DIAGRAM]} timezone={null} />)
    expect(screen.getAllByText('シーケンス図').length).toBeGreaterThan(0)
  })

  it('クリックすると詳細モーダルを開く', () => {
    render(<DiagramGalleryGrid diagrams={[DIAGRAM]} timezone={null} />)
    fireEvent.click(screen.getByAltText('シーケンス図'))
    expect(screen.getByText('ID')).toBeInTheDocument()
  })

  it('削除ボタンで確認後にdeleteDiagramActionを呼ぶ', async () => {
    jest.spyOn(window, 'confirm').mockReturnValue(true)
    render(<DiagramGalleryGrid diagrams={[DIAGRAM]} timezone={null} />)
    fireEvent.click(screen.getAllByAltText('シーケンス図')[0])
    fireEvent.click(screen.getByText('削除'))

    await waitFor(() => {
      expect(actions.deleteDiagramAction).toHaveBeenCalledWith(1)
    })
  })

  it('確認をキャンセルするとdeleteDiagramActionを呼ばない', () => {
    jest.spyOn(window, 'confirm').mockReturnValue(false)
    render(<DiagramGalleryGrid diagrams={[DIAGRAM]} timezone={null} />)
    fireEvent.click(screen.getAllByAltText('シーケンス図')[0])
    fireEvent.click(screen.getByText('削除'))

    expect(actions.deleteDiagramAction).not.toHaveBeenCalled()
  })
})
