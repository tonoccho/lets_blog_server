import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { OperationGroupRow } from '../OperationGroupRow'
import * as actions from '../actions'
import type { OperationGroup } from '../operationGroups'

jest.mock('../actions', () => ({
  copyOperationTraceAction: jest.fn(),
}))

const group: OperationGroup = {
  operationId: 'op-1',
  startedAt: '2026-08-09T00:00:00',
  success: true,
  entries: [
    {
      id: 1,
      operationId: 'op-1',
      userId: 1,
      method: 'GET',
      path: '/api/sites',
      statusCode: 200,
      durationMs: 12,
      success: true,
      errorMessage: null,
      createdAt: '2026-08-09T00:00:00',
    },
  ],
}

describe('OperationGroupRow', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } })
    ;(actions.copyOperationTraceAction as jest.Mock).mockResolvedValue('操作ID: op-1\n(trace text)')
  })

  it('先頭の呼び出しとステータスを表示する', () => {
    render(<OperationGroupRow group={group} timezone="Asia/Tokyo" />)
    expect(screen.getByText(/GET \/api\/sites/)).toBeInTheDocument()
    expect(screen.getByText('成功')).toBeInTheDocument()
  })

  it('コピーボタンをクリックするとサーバーから取得し直したトレースをクリップボードにコピーする', async () => {
    render(<OperationGroupRow group={group} timezone="Asia/Tokyo" />)
    fireEvent.click(screen.getByText('コピー'))

    await waitFor(() => {
      expect(actions.copyOperationTraceAction).toHaveBeenCalledWith('op-1')
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith('操作ID: op-1\n(trace text)')
    })
    expect(await screen.findByText('コピーしました')).toBeInTheDocument()
  })

  it('サーバーからの取得に失敗しても表示中の内容でコピーする', async () => {
    ;(actions.copyOperationTraceAction as jest.Mock).mockRejectedValue(new Error('network error'))
    render(<OperationGroupRow group={group} timezone="Asia/Tokyo" />)
    fireEvent.click(screen.getByText('コピー'))

    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining('操作ID: op-1'))
    })
  })

  it('行をクリックすると呼び出し詳細が展開される', () => {
    render(<OperationGroupRow group={group} timezone="Asia/Tokyo" />)
    expect(screen.queryByText('所要時間')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText(/GET \/api\/sites/))

    expect(screen.getByText('所要時間')).toBeInTheDocument()
  })
})
