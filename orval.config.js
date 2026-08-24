// マルチサービス構成向けのorval設定(#555)。各サービスは springdoc の既定パス
// (/v3/api-docs)でOpenAPI specを公開し、scripts/generate-api-client.sh がそれぞれを
// openapi/<サービス名>.json として取得したうえで、このファイル1回の実行(`npx orval`)で
// 全ターゲットをまとめて生成する。
//
// この Issue (#555) の時点ではサービスは legacy-api のみ。将来のサービス抽出Issue
// (Phase 19)で、下のコメント例のようにターゲットを追加していく
// (docs/API_CLIENT_GENERATION.md 参照)。
const commonOutput = {
  client: 'fetch',
  mode: 'tags-split',
  prettier: true,
  httpClient: 'fetch',
};

module.exports = {
  legacyApi: {
    input: {
      target: './openapi/legacy-api.json',
    },
    output: {
      ...commonOutput,
      target: './sdk/api-client/src/generated/legacy-api',
      baseUrl: 'http://localhost:8080',
    },
  },

  // ログの所有権をlog-writerへ完全移管(#572)。読み取りAPI(監査ログ・操作ログ・
  // フロントエンドエラーログ)をlog-writerへ移設したことに伴うターゲット追加。
  logWriter: {
    input: {
      target: './openapi/log-writer.json',
    },
    output: {
      ...commonOutput,
      target: './sdk/api-client/src/generated/log-writer',
      baseUrl: 'http://localhost:8080',
    },
  },

  // ComfyUI画像生成・draw.ioダイアグラム・PlantUML/Recharts/Penpotレンダリングをmedia-serviceへ
  // 移設(#573)。
  media: {
    input: {
      target: './openapi/media.json',
    },
    output: {
      ...commonOutput,
      target: './sdk/api-client/src/generated/media',
      baseUrl: 'http://localhost:8080',
    },
  },

  // 将来のサービス抽出Issueで追加する例(identity-serviceの場合):
  // identity: {
  //   input: { target: './openapi/identity.json' },
  //   output: {
  //     ...commonOutput,
  //     target: './sdk/api-client/src/generated/identity',
  //     baseUrl: 'http://localhost:8080/identity',
  //   },
  // },
};
