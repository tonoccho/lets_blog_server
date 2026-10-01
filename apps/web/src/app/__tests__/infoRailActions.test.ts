const mockRequireSession = jest.fn()
const mockGetViewerTimeZone = jest.fn()
jest.mock('@/lib/session', () => ({
  requireSession: () => mockRequireSession(),
  getViewerTimeZone: () => mockGetViewerTimeZone(),
}))
const mockList = jest.fn()
const mockListJobs = jest.fn()
const mockGetJob = jest.fn()
jest.mock('@/lib/apiClient', () => ({
  listUnifiedOperationLogs: (...args: unknown[]) => mockList(...args),
  listGenerationJobs: () => mockListJobs(),
  getGenerationJob: (...args: unknown[]) => mockGetJob(...args),
}))

import { fetchQueueJobsAction, fetchRecentOperationLogsAction } from '../infoRailActions'

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

function job(id: number, type: string, status: string, createdAt = `2026-09-30T10:${String(id).padStart(2, '0')}:00`) {
  return { id, type, status, createdAt, updatedAt: createdAt }
}

describe('fetchQueueJobsAction (#1407)', () => {
  beforeEach(() => {
    mockListJobs.mockResolvedValue([])
    mockGetJob.mockResolvedValue({ requestPayload: null })
  })

  it('requires a session and does not call the API when the check throws', async () => {
    mockRequireSession.mockRejectedValue(new Error('redirect'))
    await expect(fetchQueueJobsAction()).rejects.toThrow('redirect')
    expect(mockListJobs).not.toHaveBeenCalled()
  })

  it('returns the 10 most recent jobs, newest first, with the viewer time zone', async () => {
    mockListJobs.mockResolvedValue(Array.from({ length: 12 }, (_, i) => job(i + 1, 'image_generation', 'running')))
    const result = await fetchQueueJobsAction()
    expect(result.jobs.map((j) => j.id)).toEqual([12, 11, 10, 9, 8, 7, 6, 5, 4, 3])
    expect(result.timeZone).toBe('Asia/Tokyo')
  })

  it('breaks createdAt ties by the larger id first', async () => {
    const t = '2026-09-30T10:00:00'
    mockListJobs.mockResolvedValue([job(1, 'a', 'done', t), job(2, 'a', 'done', t)])
    const result = await fetchQueueJobsAction()
    expect(result.jobs.map((j) => j.id)).toEqual([2, 1])
  })

  it('gives a done checkpoint download the project list link without fetching its detail', async () => {
    mockListJobs.mockResolvedValue([job(1, 'comfyui_checkpoint_download', 'done')])
    const result = await fetchQueueJobsAction()
    expect(result.jobs[0]).toEqual({
      id: 1,
      type: 'comfyui_checkpoint_download',
      status: 'done',
      createdAt: '2026-09-30T10:01:00',
      resultHref: '/projects',
    })
    expect(mockGetJob).not.toHaveBeenCalled()
  })

  it('derives a done garbage collection link from the job detail request payload', async () => {
    mockListJobs.mockResolvedValue([job(5, 'media_garbage_collection_delete', 'done')])
    mockGetJob.mockResolvedValue({ requestPayload: '{"projectId":3}' })
    const result = await fetchQueueJobsAction()
    expect(mockGetJob).toHaveBeenCalledWith(5)
    expect(result.jobs[0].resultHref).toBe('/projects/3?tab=garbage-collection')
  })

  it('gives no link when the detail of a done garbage collection cannot be read', async () => {
    mockListJobs.mockResolvedValue([job(5, 'media_garbage_collection_delete', 'done')])
    mockGetJob.mockRejectedValue(new Error('404'))
    const result = await fetchQueueJobsAction()
    expect(result.jobs[0].resultHref).toBeNull()
  })

  it('gives no link to running, failed or unknown-type jobs, and fetches no detail for them', async () => {
    mockListJobs.mockResolvedValue([
      job(1, 'media_garbage_collection_delete', 'running'),
      job(2, 'media_garbage_collection_delete', 'failed'),
      job(3, 'image_generation', 'done'),
    ])
    const result = await fetchQueueJobsAction()
    expect(result.jobs.map((j) => j.resultHref)).toEqual([null, null, null])
    expect(mockGetJob).not.toHaveBeenCalled()
  })
})
