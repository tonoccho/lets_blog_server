import { QUEUE_JOB_LIMIT, isActiveJobStatus, resolveResultHref } from '../infoRailQueue'

describe('infoRailQueue (#1407)', () => {
  it('limits the queue to the 10 most recent jobs', () => {
    expect(QUEUE_JOB_LIMIT).toBe(10)
  })

  it.each([
    ['running', true],
    ['pending', true],
    ['done', false],
    ['failed', false],
    ['something-else', false],
  ])('isActiveJobStatus(%s) = %s', (status, expected) => {
    expect(isActiveJobStatus(status)).toBe(expected)
  })

  describe('resolveResultHref', () => {
    it('sends a checkpoint download to the project list, since its payload has no project id', () => {
      expect(resolveResultHref('comfyui_checkpoint_download', '{"url":"https://x/y","fileName":"a.safetensors"}')).toBe(
        '/projects'
      )
      expect(resolveResultHref('comfyui_checkpoint_download', null)).toBe('/projects')
    })

    it('sends a garbage collection to its project tab using the project id in the request payload', () => {
      expect(
        resolveResultHref('media_garbage_collection_delete', '{"projectId":7,"environment":"local","mediaIds":["1"]}')
      ).toBe('/projects/7?tab=garbage-collection')
    })

    it.each([
      ['no payload', null],
      ['broken JSON', '{not json'],
      ['non-object JSON', '42'],
      ['no projectId', '{"environment":"local"}'],
      ['non-numeric projectId', '{"projectId":"7"}'],
    ])('gives no link for a garbage collection with %s', (_label, payload) => {
      expect(resolveResultHref('media_garbage_collection_delete', payload)).toBeNull()
    })

    it('gives no link for an unknown job type', () => {
      expect(resolveResultHref('image_generation', '{"projectId":7}')).toBeNull()
    })
  })
})
