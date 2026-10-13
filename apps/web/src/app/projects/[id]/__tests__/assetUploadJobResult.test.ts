import {
  ASSET_UPLOAD_ACCEPTED_MESSAGE,
  ASSET_UPLOAD_QUEUE_FULL_ERROR,
  describeAssetUploadJob,
} from '../assetUploadJobResult'

/** issue #1764: アセット画像の全環境アップロードのジョブ(`asset_image_upload`)の結果を、パネルのメッセージへ読み替える。 */
describe('describeAssetUploadJob (#1764)', () => {
  const done = (logs: unknown) => ({ status: 'done', resultPayload: JSON.stringify({ phase: 'done', logs }) })

  it('全環境が成功なら、従来と同じ「全N環境へアップロードしました。」を成功として示す', () => {
    expect(
      describeAssetUploadJob(
        done([
          { environment: 'local', status: 'SUCCESS' },
          { environment: 'test', status: 'SUCCESS' },
        ])
      )
    ).toEqual({ type: 'success', text: '全2環境へアップロードしました。' })
  })

  it('失敗した環境があれば、従来と同じく環境名を並べた失敗を示す(画素数の上限による拒否も FAILED のログ)', () => {
    expect(
      describeAssetUploadJob(
        done([
          { environment: 'local', status: 'SUCCESS' },
          { environment: 'test', status: 'FAILED' },
          { environment: 'production', status: 'FAILED' },
        ])
      )
    ).toEqual({ type: 'error', text: 'test, production環境でアップロードに失敗しました。' })
  })

  it('ログが空なら全0環境として扱う', () => {
    expect(describeAssetUploadJob(done([]))).toEqual({ type: 'success', text: '全0環境へアップロードしました。' })
  })

  it.each([
    ['logs が無い', { status: 'done', resultPayload: '{"phase":"done"}' }],
    ['resultPayload が null', { status: 'done', resultPayload: null }],
    ['resultPayload が壊れた JSON', { status: 'done', resultPayload: '{' }],
    ['resultPayload が JSON の配列でない値', { status: 'done', resultPayload: '3' }],
  ])('done でも結果を読み取れない(%s)ときは、その旨のエラーを示す', (_label, job) => {
    expect(describeAssetUploadJob(job)).toEqual({ type: 'error', text: '結果を読み取れませんでした。' })
  })

  it('failed は resultPayload.error の理由を示す', () => {
    expect(
      describeAssetUploadJob({ status: 'failed', resultPayload: '{"error":"project not found","errorType":"project_not_found"}' })
    ).toEqual({ type: 'error', text: 'project not found' })
  })

  it('failed で理由が読めなければ既定の失敗文言を示す', () => {
    expect(describeAssetUploadJob({ status: 'failed', resultPayload: null })).toEqual({
      type: 'error',
      text: 'アセットとしての追加に失敗しました。',
    })
    expect(describeAssetUploadJob({ status: 'failed', resultPayload: '{"error":3}' }).text).toBe(
      'アセットとしての追加に失敗しました。'
    )
  })

  it.each(['pending', 'running'])('%s は実行中として案内する', (status) => {
    expect(describeAssetUploadJob({ status, resultPayload: null })).toEqual({
      type: 'info',
      text: 'まだ実行中です。完了後に、処理キューの「結果を見る」からもう一度開いてください。',
    })
  })

  it('受理とキュー満杯の文言を持つ', () => {
    expect(ASSET_UPLOAD_ACCEPTED_MESSAGE).toContain('処理キューに追加されました')
    expect(ASSET_UPLOAD_QUEUE_FULL_ERROR).toContain('待ち行列が満杯')
  })
})
