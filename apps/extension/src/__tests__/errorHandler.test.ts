import {
  ApiError,
  CancelledError,
  NetworkError,
  ResponseValidationError,
  TimeoutError,
  describeError,
} from '../errorHandler';
import { resetMocks } from '../__mocks__/vscode';

beforeEach(() => {
  resetMocks();
});

describe('describeError: 下流サービス障害の切り分け(issue #585)', () => {
  it('5xxでは、どのサービスが失敗したかとログの見方を示す', () => {
    const message = describeError(
      new ApiError('APIエラー (503)', 503, '', 'https://localhost/api/ai/section')
    );
    expect(message).toContain('AI生成サービス');
    expect(message).toContain('lbs-ai');
    expect(message).toContain('/api/ai/section');
    expect(message).toContain('docker logs lbs-ai');
  });

  it('5xxで相関IDが取れていればログ横断検索の手掛かりとして添える', () => {
    const message = describeError(
      new ApiError('APIエラー (500)', 500, '', 'https://localhost/api/diagrams/1', 'corr-1234')
    );
    expect(message).toContain('画像・ダイアグラムサービス');
    expect(message).toContain('相関ID: corr-1234');
  });

  it('相関IDが取れていない場合は相関IDの行を出さない', () => {
    const message = describeError(
      new ApiError('APIエラー (500)', 500, '', 'https://localhost/api/diagrams/1')
    );
    expect(message).not.toContain('相関ID');
  });

  it('パスごとに担当サービスを出し分ける(記事公開はpublishing-service)', () => {
    const message = describeError(
      new ApiError('APIエラー (502)', 502, '', 'https://localhost/api/posts/publish')
    );
    expect(message).toContain('記事公開サービス');
    expect(message).toContain('lbs-publishing');
  });

  describe('502: 応答本文の有無で到達不能の断定を出し分ける(issue #1082)', () => {
    const url = 'https://localhost/api/ai/image';
    const body = '{"error":"ComfyUIへのジョブ投入に失敗しました: ckpt_name not in []"}';

    it('応答本文を伴う502では「到達できませんでした」と断定しない', () => {
      const message = describeError(new ApiError('APIエラー (502)', 502, body, url, 'corr-1'));
      expect(message).not.toContain('到達できませんでした');
      expect(message).not.toContain('コンテナが起動しているか');
      expect(message).toContain('上流');
    });

    it('応答本文を伴う502では、サーバー応答をステータス直後(担当サービスの案内より前)に置く', () => {
      const message = describeError(new ApiError('APIエラー (502)', 502, body, url, 'corr-1'));
      expect(message.startsWith(`APIエラー (502) サーバーからの応答: ${body}`)).toBe(true);
      expect(message.indexOf('サーバーからの応答')).toBeLessThan(message.indexOf('担当サービス'));
    });

    it('応答本文を伴う502でも相関IDと担当サービス名は維持する', () => {
      const message = describeError(new ApiError('APIエラー (502)', 502, body, url, 'corr-1'));
      expect(message).toContain('相関ID: corr-1');
      expect(message).toContain('画像・ダイアグラムサービス');
    });

    it('空白のみの本文は本文なしとして扱い、従来の到達不能の説明を出す', () => {
      const message = describeError(new ApiError('APIエラー (502)', 502, '  \n', url));
      expect(message).toContain('gatewayが担当サービスへ到達できませんでした');
      expect(message).toContain('担当サービスのコンテナが起動しているか確認してください');
    });

    it('応答本文なしの502は従来どおり到達不能とコンテナ確認を案内する', () => {
      const message = describeError(new ApiError('APIエラー (502)', 502, '', url, 'corr-2'));
      expect(message).toContain('gatewayが担当サービスへ到達できませんでした');
      expect(message).toContain('担当サービスのコンテナが起動しているか確認してください');
      expect(message).toContain('docker logs lbs-media');
      expect(message).toContain('相関ID: corr-2');
      expect(message).not.toContain('サーバーからの応答');
    });
  });

  it('4xxは利用者の操作起因が大半のため、担当サービス名は付けない', () => {
    const message = describeError(
      new ApiError('APIエラー (404)', 404, 'not found', 'https://localhost/api/projects/3')
    );
    expect(message).toContain('対象のリソースが見つかりませんでした。');
    expect(message).not.toContain('担当サービス');
  });

  it('レスポンス本文があれば従来どおり併記する', () => {
    const message = describeError(
      new ApiError('APIエラー (500)', 500, '{"error":"boom"}', 'https://localhost/api/ai/draft')
    );
    expect(message).toContain('サーバーからの応答: {"error":"boom"}');
  });

  it('タイムアウトでは、その処理を担当するサービスを調査の起点として示す', () => {
    const message = describeError(
      new TimeoutError('timed out', 'https://localhost/api/projects/3/preview/render', 120_000)
    );
    expect(message).toContain('120 秒以内に返りませんでした');
    expect(message).toContain('記事コンテンツサービス');
    expect(message).toContain('lbs-content');
  });

  it('接続不可では個々の下流サービスではなく到達経路の問題であることを示す', () => {
    const message = describeError(
      new NetworkError('unreachable', 'https://localhost/api/ai/draft', new Error('ECONNREFUSED'))
    );
    expect(message).toContain('APIゲートウェイ');
    expect(message).toContain('到達経路の問題');
    // 到達していない以上、特定の下流サービスを名指ししてはならない(誤誘導になるため)。
    expect(message).not.toContain('AI生成サービス');
    expect(message).not.toContain('担当サービス');
  });
});

describe('describeError: 既存の分岐', () => {
  it('キャンセルは失敗として扱わない', () => {
    expect(describeError(new CancelledError('cancelled'))).toBe('操作をキャンセルしました。');
  });

  it('スキーマ検証エラーは不一致の内訳を示す', () => {
    const message = describeError(
      new ResponseValidationError('invalid', 'https://localhost/api/sites', ['0.siteKey: Required'])
    );
    expect(message).toContain('0.siteKey: Required');
  });

  it('その他の例外はメッセージをそのまま返す', () => {
    expect(describeError(new Error('なにか失敗'))).toBe('なにか失敗');
  });
});

describe('describeError: 401の案内が現行の認証方式に沿っていること(issue #774)', () => {
  // #565でDevice Authorization Grantへ移行し、#566で旧認証機構(APIキー)と
  // 「Let's Blog: Set API Key」コマンドが撤去された。401は利用者が最も遭遇しやすい
  // エラーの1つであり、ここで存在しない操作を案内すると確実に行き止まりへ誘導する。
  const message = () =>
    describeError(new ApiError('APIエラー (401)', 401, '', 'https://localhost/api/projects'));

  it('撤去済みの「Set API Key」コマンドに言及しない', () => {
    expect(message()).not.toContain('Set API Key');
  });

  it('現在存在しない「APIキー」ではなく現行の用語を使う', () => {
    expect(message()).not.toContain('APIキー');
    expect(message()).toContain('アクセストークン');
  });

  it('実行可能な対処として「Let\'s Blog: Login」での再ログインを案内する', () => {
    expect(message()).toContain("Let's Blog: Login");
  });
});
