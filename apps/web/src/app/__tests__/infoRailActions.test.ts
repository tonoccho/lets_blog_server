jest.mock('server-only', () => ({}))
// 自前の @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤(next-auth・
// next/headers・next/server・next/navigation の redirect)と、外部との通信(fetch)だけ(#1832)。
// fetch の向こうのバックエンド(gateway)の振る舞いは、下の jest.fn() で書く(自前モジュールの代役ではない)。
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`redirect:${path}`)
  },
}))

import { fetchQueueJobsAction, fetchRecentOperationLogsAction } from '../infoRailActions'
import {
  getServerSessionMock as mockRequireSession,
  installFetch,
  resetServerRuntime,
  respondWith,
} from '@/lib/__tests__/testUtils/serverRuntime'

// バックエンド(gateway)の代役。
const mockGetMe = jest.fn()
const mockList = jest.fn()
const mockListJobs = jest.fn()
const mockGetJob = jest.fn()
const mockGetImage = jest.fn()

beforeEach(() => {
  jest.clearAllMocks()
  resetServerRuntime({ user: {} })
  installFetch({
    'GET /api/identity/me': respondWith(() => mockGetMe()),
    'GET /api/operation-logs/unified?page=0&size=10': respondWith(() => mockList()),
    'GET /api/generation-jobs': respondWith(() => mockListJobs()),
    'GET /api/generation-jobs/:id': respondWith((id) => mockGetJob(Number(id))),
    'GET /api/generated-images/:id': respondWith((id) => mockGetImage(Number(id))),
  })
  mockGetMe.mockResolvedValue({ timezone: 'Asia/Tokyo' })
  mockList.mockResolvedValue({ content: [{ id: 1 }] })
})

describe('fetchRecentOperationLogsAction (#1489)', () => {
  it('requires a session, fetches only the newest page of 10, without filters, and returns the viewer time zone', async () => {
    const result = await fetchRecentOperationLogsAction()
    expect(mockRequireSession).toHaveBeenCalled()
    // 件数 10・先頭ページ・絞り込みなしは、リクエスト URL(上のルートのキー)で固定される。
    expect(mockList).toHaveBeenCalledTimes(1)
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

describe('fetchQueueJobsAction site provisioning (#1696)', () => {
  it('links a done site provisioning job to the edit screen of the site named in its result', async () => {
    mockListJobs.mockResolvedValue([job(9, 'site_provisioning', 'done')])
    mockGetJob.mockResolvedValue({ resultPayload: '{"siteId":12}' })
    const result = await fetchQueueJobsAction()
    expect(mockGetJob).toHaveBeenCalledWith(9)
    expect(result.jobs[0].resultHref).toBe('/sites/12/edit')
  })

  it.each([
    ['has no site id', { resultPayload: '{"phase":"registering"}' }],
    ['has no result', { resultPayload: null }],
  ])('gives no link when the done result %s', async (_label, detail) => {
    mockListJobs.mockResolvedValue([job(9, 'site_provisioning', 'done')])
    mockGetJob.mockResolvedValue(detail)
    expect((await fetchQueueJobsAction()).jobs[0].resultHref).toBeNull()
  })

  it('gives no link when the detail of a done site provisioning job cannot be read', async () => {
    mockListJobs.mockResolvedValue([job(9, 'site_provisioning', 'done')])
    mockGetJob.mockRejectedValue(new Error('404'))
    expect((await fetchQueueJobsAction()).jobs[0].resultHref).toBeNull()
  })

  it('does not fetch the detail of a running site provisioning job and gives it no link', async () => {
    mockListJobs.mockResolvedValue([job(9, 'site_provisioning', 'running')])
    const result = await fetchQueueJobsAction()
    expect(mockGetJob).not.toHaveBeenCalled()
    expect(result.jobs[0].resultHref).toBeNull()
  })
})

describe('fetchQueueJobsAction environment sync (#1697)', () => {
  it('derives the done link from the request payload project id', async () => {
    mockListJobs.mockResolvedValue([job(9, 'environment_sync', 'done')])
    mockGetJob.mockResolvedValue({ requestPayload: '{"projectId":7,"from":"test","to":"local","targets":["db"]}' })
    const result = await fetchQueueJobsAction()
    expect(mockGetJob).toHaveBeenCalledWith(9)
    expect(result.jobs[0].resultHref).toBe('/projects/7?tab=settings')
  })

  it('gives no link when the detail cannot be read', async () => {
    mockListJobs.mockResolvedValue([job(9, 'environment_sync', 'done')])
    mockGetJob.mockRejectedValue(new Error('404'))
    const result = await fetchQueueJobsAction()
    expect(result.jobs[0].resultHref).toBeNull()
  })

  it('gives no link to a running sync and fetches no detail for it', async () => {
    mockListJobs.mockResolvedValue([job(9, 'environment_sync', 'running')])
    const result = await fetchQueueJobsAction()
    expect(result.jobs[0].resultHref).toBeNull()
    expect(mockGetJob).not.toHaveBeenCalled()
  })
})

describe('fetchQueueJobsAction bulk install (#1702)', () => {
  it.each(['bulk_slug_install', 'bulk_zip_install'])('derives the done link of a %s job from the request payload', async (type) => {
    mockListJobs.mockResolvedValue([job(9, type, 'done')])
    mockGetJob.mockResolvedValue({ requestPayload: '{"projectId":7,"operationType":"PLUGIN_INSTALL"}' })
    const result = await fetchQueueJobsAction()
    expect(mockGetJob).toHaveBeenCalledWith(9)
    expect(result.jobs[0].resultHref).toBe('/projects/7?tab=maintenance&bulkJob=9')
  })

  it('gives no link when the detail cannot be read', async () => {
    mockListJobs.mockResolvedValue([job(9, 'bulk_slug_install', 'done')])
    mockGetJob.mockRejectedValue(new Error('404'))
    const result = await fetchQueueJobsAction()
    expect(result.jobs[0].resultHref).toBeNull()
  })

  it('gives no link to a running install and fetches no detail for it', async () => {
    mockListJobs.mockResolvedValue([job(9, 'bulk_zip_install', 'running')])
    const result = await fetchQueueJobsAction()
    expect(result.jobs[0].resultHref).toBeNull()
    expect(mockGetJob).not.toHaveBeenCalled()
  })
})

describe('fetchQueueJobsAction asset image upload (#1764)', () => {
  it('derives the done link of an asset_image_upload job from the request payload', async () => {
    mockListJobs.mockResolvedValue([job(9, 'asset_image_upload', 'done')])
    mockGetJob.mockResolvedValue({ requestPayload: '{"projectId":7,"generatedImageId":11}' })
    const result = await fetchQueueJobsAction()
    expect(mockGetJob).toHaveBeenCalledWith(9)
    expect(result.jobs[0].resultHref).toBe('/projects/7?tab=ai-models&assetUploadJob=9')
  })

  it('gives no link when the detail cannot be read', async () => {
    mockListJobs.mockResolvedValue([job(9, 'asset_image_upload', 'done')])
    mockGetJob.mockRejectedValue(new Error('404'))
    const result = await fetchQueueJobsAction()
    expect(result.jobs[0].resultHref).toBeNull()
  })

  it('gives no link to a running upload and fetches no detail for it', async () => {
    mockListJobs.mockResolvedValue([job(9, 'asset_image_upload', 'running')])
    const result = await fetchQueueJobsAction()
    expect(result.jobs[0].resultHref).toBeNull()
    expect(mockGetJob).not.toHaveBeenCalled()
  })
})
