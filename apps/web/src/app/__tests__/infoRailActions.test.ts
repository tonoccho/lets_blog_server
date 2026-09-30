const mockRequireSession = jest.fn()
const mockGetViewerTimeZone = jest.fn()
jest.mock('@/lib/session', () => ({
  requireSession: () => mockRequireSession(),
  getViewerTimeZone: () => mockGetViewerTimeZone(),
}))
const mockList = jest.fn()
jest.mock('@/lib/apiClient', () => ({
  listUnifiedOperationLogs: (...args: unknown[]) => mockList(...args),
}))

import { fetchRecentOperationLogsAction } from '../infoRailActions'

beforeEach(() => {
  jest.clearAllMocks()
  mockRequireSession.mockResolvedValue({ user: {} })
  mockGetViewerTimeZone.mockResolvedValue('Asia/Tokyo')
  mockList.mockResolvedValue({ content: [{ id: 1 }] })
})

describe('fetchRecentOperationLogsAction (#1489)', () => {
  it('requires a session, fetches only the newest page of 10, without filters, and returns the viewer time zone', async () => {
    const result = await fetchRecentOperationLogsAction()
    expect(mockRequireSession).toHaveBeenCalled()
    expect(mockList).toHaveBeenCalledWith({ page: 0, size: 10 })
    expect(result).toEqual({ entries: [{ id: 1 }], timeZone: 'Asia/Tokyo' })
  })

  it('does not call the API when the session check throws', async () => {
    mockRequireSession.mockRejectedValue(new Error('redirect'))
    await expect(fetchRecentOperationLogsAction()).rejects.toThrow('redirect')
    expect(mockList).not.toHaveBeenCalled()
  })
})
