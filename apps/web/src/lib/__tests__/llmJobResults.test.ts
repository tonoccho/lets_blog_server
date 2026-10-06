/**
 * LLM生成ジョブ(カスタムタグ・静的コンテンツ・タグデザイン、issue #1409)の `result_payload` の読み取りと、
 * 「結果を見る」の遷移先画面がジョブIDからその結果を引く処理。生成結果は保存先ではなく
 * ジョブの結果にだけあるので、画面はここを通して読み、「保存」を押したときに初めて保存APIへ渡す。
 */
jest.mock('server-only', () => ({}))

const mockGetGenerationJob = jest.fn()
jest.mock('@/lib/apiClient', () => ({
  getGenerationJob: (...args: unknown[]) => mockGetGenerationJob(...args),
}))

import {
  loadLlmJobResult,
  readCustomTagJobResult,
  readStaticContentJobResult,
  readTagDesignJobResult,
} from '../llmJobResults'

describe('readCustomTagJobResult', () => {
  it('reads the generated tag out of the result payload', () => {
    expect(
      readCustomTagJobResult(
        '{"tagName":"blue","description":"青","projectId":7,"htmlTemplate":"<b>{{content}}</b>","cssContent":".b{}"}'
      )
    ).toEqual({ tagName: 'blue', description: '青', projectId: 7, htmlTemplate: '<b>{{content}}</b>', cssContent: '.b{}' })
  })

  it('keeps a missing description and project as null (a global tag)', () => {
    expect(
      readCustomTagJobResult('{"tagName":"blue","description":null,"projectId":null,"htmlTemplate":"<b/>","cssContent":""}')
    ).toEqual({ tagName: 'blue', description: null, projectId: null, htmlTemplate: '<b/>', cssContent: '' })
  })

  it.each([
    ['no payload', null],
    ['broken JSON', '{nope'],
    ['non-object JSON', '42'],
    ['null JSON', 'null'],
    ['no tag name', '{"htmlTemplate":"<b/>"}'],
    ['no html', '{"tagName":"a","cssContent":".b{}"}'],
    ['an error result', '{"error":"boom","errorType":"invalid_content"}'],
  ])('reads nothing from %s', (_label, payload) => {
    expect(readCustomTagJobResult(payload)).toBeNull()
  })

  it('treats a missing css as empty and ignores a non-string description / non-numeric project', () => {
    expect(
      readCustomTagJobResult('{"tagName":"a","htmlTemplate":"<b/>","description":3,"projectId":"7"}')
    ).toEqual({ tagName: 'a', description: null, projectId: null, htmlTemplate: '<b/>', cssContent: '' })
  })
})

describe('readStaticContentJobResult', () => {
  it('reads the generated body, the site and the content type', () => {
    expect(readStaticContentJobResult('{"siteId":3,"contentType":"OPERATOR_INFO","body":"本文"}')).toEqual({
      siteId: 3,
      contentType: 'OPERATOR_INFO',
      body: '本文',
    })
  })

  it.each([
    ['no payload', null],
    ['broken JSON', '{nope'],
    ['non-object JSON', '[]'],
    ['no site', '{"contentType":"OPERATOR_INFO","body":"x"}'],
    ['an unknown content type', '{"siteId":3,"contentType":"SOMETHING","body":"x"}'],
    ['no body', '{"siteId":3,"contentType":"OPERATOR_INFO"}'],
    ['an error result', '{"error":"boom"}'],
  ])('reads nothing from %s', (_label, payload) => {
    expect(readStaticContentJobResult(payload)).toBeNull()
  })
})

describe('readTagDesignJobResult', () => {
  it('reads a project tag design', () => {
    expect(
      readTagDesignJobResult('{"projectId":7,"tagType":"TOC","htmlTemplate":"<nav/>","cssContent":".a{}"}')
    ).toEqual({ projectId: 7, tagType: 'TOC', htmlTemplate: '<nav/>', cssContent: '.a{}' })
  })

  it('reads a global tag design (project null) and an empty html template', () => {
    expect(
      readTagDesignJobResult('{"projectId":null,"tagType":"BLOGCARD","htmlTemplate":"","cssContent":".a{}"}')
    ).toEqual({ projectId: null, tagType: 'BLOGCARD', htmlTemplate: '', cssContent: '.a{}' })
  })

  it('treats a missing project and a missing html template as global / empty', () => {
    expect(readTagDesignJobResult('{"tagType":"AMAZON","cssContent":".a{}"}')).toEqual({
      projectId: null,
      tagType: 'AMAZON',
      htmlTemplate: '',
      cssContent: '.a{}',
    })
  })

  it.each([
    ['no payload', null],
    ['broken JSON', '{nope'],
    ['non-object JSON', '1'],
    ['an unknown tag type', '{"tagType":"X","cssContent":".a{}"}'],
    ['no css', '{"tagType":"TOC","htmlTemplate":"<nav/>"}'],
    ['an error result', '{"error":"boom"}'],
  ])('reads nothing from %s', (_label, payload) => {
    expect(readTagDesignJobResult(payload)).toBeNull()
  })
})

describe('loadLlmJobResult', () => {
  const read = (payload: string | null) => (payload === 'ok' ? { value: 1 } : null)

  beforeEach(() => {
    mockGetGenerationJob.mockReset()
  })

  it('reads the done job of the expected type through the user token API and names the job id', async () => {
    mockGetGenerationJob.mockResolvedValue({ id: 9, type: 'tag_design_generation', status: 'done', resultPayload: 'ok' })
    await expect(loadLlmJobResult('9', 'tag_design_generation', read)).resolves.toEqual({ value: 1, jobId: 9 })
    expect(mockGetGenerationJob).toHaveBeenCalledWith(9)
  })

  it.each([undefined, '', 'abc', '0', '-3', '1.5'])('does not call the API for the job id %p', async (raw) => {
    await expect(loadLlmJobResult(raw, 'tag_design_generation', read)).resolves.toBeNull()
    expect(mockGetGenerationJob).not.toHaveBeenCalled()
  })

  it('gives nothing when the job cannot be read (not found / not the viewer\'s job)', async () => {
    mockGetGenerationJob.mockRejectedValue(new Error('404'))
    await expect(loadLlmJobResult('9', 'tag_design_generation', read)).resolves.toBeNull()
  })

  it('gives nothing for a job of another type, a job that is not done, or an unreadable result', async () => {
    mockGetGenerationJob.mockResolvedValueOnce({ id: 9, type: 'image_generation', status: 'done', resultPayload: 'ok' })
    await expect(loadLlmJobResult('9', 'tag_design_generation', read)).resolves.toBeNull()
    mockGetGenerationJob.mockResolvedValueOnce({ id: 9, type: 'tag_design_generation', status: 'running', resultPayload: 'ok' })
    await expect(loadLlmJobResult('9', 'tag_design_generation', read)).resolves.toBeNull()
    mockGetGenerationJob.mockResolvedValueOnce({ id: 9, type: 'tag_design_generation', status: 'done', resultPayload: null })
    await expect(loadLlmJobResult('9', 'tag_design_generation', read)).resolves.toBeNull()
  })
})
