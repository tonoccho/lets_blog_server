const mockRequireSession = jest.fn()
const mockGetViewerTimeZone = jest.fn()
jest.mock('@/lib/session', () => ({
  requireSession: () => mockRequireSession(),
  getViewerTimeZone: () => mockGetViewerTimeZone(),
}))
const mockList = jest.fn()
const mockListJobs = jest.fn()
const mockGetJob = jest.fn()
const mockGetImage = jest.fn()
jest.mock('@/lib/apiClient', () => ({
  listUnifiedOperationLogs: (...args: unknown[]) => mockList(...args),
  listGenerationJobs: () => mockListJobs(),
  getGenerationJob: (...args: unknown[]) => mockGetJob(...args),
  getGeneratedImage: (...args: unknown[]) => mockGetImage(...args),
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
      failureReason: null,
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

  it('gives no link to running, failed or unknown-type jobs, and fetches no detail for running or unknown ones', async () => {
    mockListJobs.mockResolvedValue([
      job(1, 'media_garbage_collection_delete', 'running'),
      job(2, 'media_garbage_collection_delete', 'failed'),
      job(3, 'something_else', 'done'),
      job(4, 'image_generation', 'running'),
      job(5, 'image_generation', 'failed'),
    ])
    const result = await fetchQueueJobsAction()
    expect(result.jobs.map((j) => j.resultHref)).toEqual([null, null, null, null, null])
    expect(mockGetJob.mock.calls.map((c) => c[0]).sort()).toEqual([2, 5])
  })
})

describe('fetchQueueJobsAction failure reason (#1571)', () => {
  beforeEach(() => {
    mockGetJob.mockResolvedValue({ resultPayload: '{"error":"ComfyUI timed out"}' })
  })

  it('carries resultPayload.error of a failed job as its failure reason', async () => {
    mockListJobs.mockResolvedValue([job(5, 'image_generation', 'failed')])
    const result = await fetchQueueJobsAction()
    expect(mockGetJob).toHaveBeenCalledWith(5)
    expect(result.jobs[0].failureReason).toBe('ComfyUI timed out')
    expect(result.jobs[0].resultHref).toBeNull()
  })

  it('gives a null reason when the failed job detail cannot be read or has no error', async () => {
    mockListJobs.mockResolvedValue([job(5, 'image_generation', 'failed'), job(6, 'image_generation', 'failed')])
    mockGetJob.mockRejectedValueOnce(new Error('404')).mockResolvedValueOnce({ resultPayload: null })
    const result = await fetchQueueJobsAction()
    expect(result.jobs.map((j) => j.failureReason)).toEqual([null, null])
  })

  it('gives a null reason to jobs that did not fail', async () => {
    mockListJobs.mockResolvedValue([job(1, 'image_generation', 'running'), job(2, 'something_else', 'done')])
    const result = await fetchQueueJobsAction()
    expect(result.jobs.map((j) => j.failureReason)).toEqual([null, null])
  })
})

describe('fetchQueueJobsAction image generation (#1408)', () => {
  beforeEach(() => {
    mockListJobs.mockResolvedValue([job(9, 'image_generation', 'done')])
    mockGetJob.mockResolvedValue({ resultPayload: '{"imageIds":[31,32]}' })
    mockGetImage.mockResolvedValue({ id: 31, projectId: 7 })
  })

  it('links a done image generation to the result screen of the project that owns its images', async () => {
    const result = await fetchQueueJobsAction()
    expect(mockGetJob).toHaveBeenCalledWith(9)
    expect(mockGetImage).toHaveBeenCalledWith(31)
    expect(result.jobs[0].resultHref).toBe('/projects/7?tab=ai-models&imageJob=9')
  })

  it('gives no link when the job produced no images', async () => {
    mockGetJob.mockResolvedValue({ resultPayload: '{"imageIds":[]}' })
    const result = await fetchQueueJobsAction()
    expect(result.jobs[0].resultHref).toBeNull()
    expect(mockGetImage).not.toHaveBeenCalled()
  })

  it('gives no link when the image has no project', async () => {
    mockGetImage.mockResolvedValue({ id: 31, projectId: null })
    expect((await fetchQueueJobsAction()).jobs[0].resultHref).toBeNull()
  })

  it('gives no link when the job detail cannot be read', async () => {
    mockGetJob.mockRejectedValue(new Error('404'))
    expect((await fetchQueueJobsAction()).jobs[0].resultHref).toBeNull()
  })

  it('gives no link when the image can no longer be read (deleted)', async () => {
    mockGetImage.mockRejectedValue(new Error('404'))
    expect((await fetchQueueJobsAction()).jobs[0].resultHref).toBeNull()
  })
})

describe('fetchQueueJobsAction LLM generation jobs (#1409)', () => {
  it.each([
    ['custom_tag_generation', '{"tagName":"a","projectId":7}', '/projects/7/tags?tab=custom-tags&customTagJob=9'],
    ['static_content_generation', '{"siteId":3,"contentType":"OPERATOR_INFO"}', '/sites/3/edit?staticContentJob=9'],
    ['tag_design_generation', '{"projectId":7,"tagType":"TOC"}', '/projects/7/tags?tab=tag-design&tagDesignJob=9'],
    ['tag_design_generation', '{"projectId":null,"tagType":"TOC"}', '/admin/tag-design?tagDesignJob=9'],
  ])('links a done %s job to its feature screen, naming the job', async (type, requestPayload, href) => {
    mockListJobs.mockResolvedValue([job(9, type, 'done')])
    mockGetJob.mockResolvedValue({ requestPayload })
    const result = await fetchQueueJobsAction()
    expect(mockGetJob).toHaveBeenCalledWith(9)
    expect(result.jobs[0].resultHref).toBe(href)
  })

  it('gives no link when the detail of a done LLM generation job cannot be read', async () => {
    mockListJobs.mockResolvedValue([job(9, 'static_content_generation', 'done')])
    mockGetJob.mockRejectedValue(new Error('404'))
    expect((await fetchQueueJobsAction()).jobs[0].resultHref).toBeNull()
  })

  it('does not fetch the detail of a running LLM generation job', async () => {
    mockListJobs.mockResolvedValue([job(9, 'custom_tag_generation', 'running')])
    const result = await fetchQueueJobsAction()
    expect(mockGetJob).not.toHaveBeenCalled()
    expect(result.jobs[0].resultHref).toBeNull()
  })
})
