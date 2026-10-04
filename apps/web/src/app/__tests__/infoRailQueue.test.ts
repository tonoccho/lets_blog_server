import {
  IMAGE_GENERATION_JOB_TYPE,
  QUEUE_JOB_LIMIT,
  FAILURE_REASON_MAX_LENGTH,
  buildImageGenerationResultHref,
  isActiveJobStatus,
  readFailureReason,
  readImageIds,
  resolveResultHref,
} from '../infoRailQueue'

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
      expect(resolveResultHref('something_else', '{"projectId":7}')).toBeNull()
    })

    it('gives no link for an image generation here, since its project is not in the request payload (see the action)', () => {
      expect(resolveResultHref(IMAGE_GENERATION_JOB_TYPE, '{"prompt":"x"}')).toBeNull()
    })
  })

  describe('image generation result (#1408)', () => {
    it('uses the job type written by the media service', () => {
      expect(IMAGE_GENERATION_JOB_TYPE).toBe('image_generation')
    })

    it('links to the AI/asset tab of the project, naming the job whose images to show', () => {
      expect(buildImageGenerationResultHref(7, 42)).toBe('/projects/7?tab=ai-models&imageJob=42')
    })

    it('reads the image ids out of the result payload', () => {
      expect(readImageIds('{"imageIds":[3,4,5],"count":3}')).toEqual([3, 4, 5])
    })

    it.each([
      ['no payload', null],
      ['broken JSON', '{nope'],
      ['non-object JSON', '7'],
      ['null JSON', 'null'],
      ['no imageIds', '{"count":0}'],
      ['imageIds that is not an array', '{"imageIds":"1,2"}'],
    ])('reads no image ids from %s', (_label, payload) => {
      expect(readImageIds(payload)).toEqual([])
    })

    it('drops entries that are not numbers', () => {
      expect(readImageIds('{"imageIds":[1,"2",null,3]}')).toEqual([1, 3])
    })
  })
})

describe('readFailureReason (#1571)', () => {
  it('reads the error text out of the failed result payload', () => {
    expect(readFailureReason('{"error":"ComfyUI timed out"}')).toBe('ComfyUI timed out')
  })

  it.each([
    ['no payload', null],
    ['broken JSON', '{nope'],
    ['non-object JSON', '7'],
    ['null JSON', 'null'],
    ['no error', '{"imageIds":[1]}'],
    ['a non-string error', '{"error":42}'],
    ['a blank error', '{"error":"   "}'],
  ])('reads no reason from %s', (_label, payload) => {
    expect(readFailureReason(payload)).toBeNull()
  })

  it('trims surrounding whitespace', () => {
    expect(readFailureReason('{"error":"  boom \\n"}')).toBe('boom')
  })

  it('keeps a reason of exactly the maximum length as is', () => {
    const exact = 'a'.repeat(FAILURE_REASON_MAX_LENGTH)
    expect(readFailureReason(JSON.stringify({ error: exact }))).toBe(exact)
  })

  it('truncates a longer reason to the maximum length with an ellipsis', () => {
    const long = 'a'.repeat(FAILURE_REASON_MAX_LENGTH + 50)
    const reason = readFailureReason(JSON.stringify({ error: long }))
    expect(reason).toBe('a'.repeat(FAILURE_REASON_MAX_LENGTH - 1) + '…')
    expect(reason).toHaveLength(FAILURE_REASON_MAX_LENGTH)
  })
})
