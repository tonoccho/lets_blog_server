import {
  IMAGE_GENERATION_JOB_TYPE,
  QUEUE_JOB_LIMIT,
  buildImageGenerationResultHref,
  isActiveJobStatus,
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
