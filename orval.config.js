// マルチサービス構成向けのorval設定(#555)。各サービスは springdoc の既定パス
// (/v3/api-docs)でOpenAPI specを公開し、scripts/generate-api-client.sh がそれぞれを
// openapi/<サービス名>.json として取得したうえで、このファイル1回の実行(`npx orval`)で
// 全ターゲットをまとめて生成する。
//
// #555 の時点ではサービスは legacy-api のみだったが、Phase 19 のサービス抽出で分割され、
// issue #583 で legacy-api 自体が削除された。現在は9サービスがターゲット
// (docs/API_CLIENT_GENERATION.md 参照)。
const commonOutput = {
  client: 'fetch',
  mode: 'tags-split',
  prettier: true,
  httpClient: 'fetch',
};

module.exports = {
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

  // LLM呼び出し(下書き/校正/要約/タグ提案/セクション生成/Ask AI)・記事プラン(壁打ち)・
  // generation_jobsをai-serviceへ移設(#574)。
  ai: {
    input: {
      target: './openapi/ai.json',
    },
    output: {
      ...commonOutput,
      target: './sdk/api-client/src/generated/ai',
      baseUrl: 'http://localhost:8080',
    },
  },

  // 記事本文(posts)・カスタムタグ・Markdownレンダリング・記事プレビュー・コンテンツキャッシュを
  // content-serviceへ移設(#576)。
  content: {
    input: {
      target: './openapi/content.json',
    },
    output: {
      ...commonOutput,
      target: './sdk/api-client/src/generated/content',
      baseUrl: 'http://localhost:8080',
    },
  },

  // Google Analytics/AdSense連携(OAuth資格情報の保管、GA4 Data API/AdSense Management APIの
  // レポート取得)をanalytics-serviceへ移設(#578)。
  analytics: {
    input: {
      target: './openapi/analytics.json',
    },
    output: {
      ...commonOutput,
      target: './sdk/api-client/src/generated/analytics',
      baseUrl: 'http://localhost:8080',
    },
  },

  // 名前をつけて保存・管理するSSH鍵ペア(Ed25519)、[toc]/[blogcard]/[amazon]組み込みタグの
  // デザインカスタマイズ(プロジェクト単位)をproject-serviceへ移設(#577 stage 1)。
  project: {
    input: {
      target: './openapi/project.json',
    },
    output: {
      ...commonOutput,
      target: './sdk/api-client/src/generated/project',
      baseUrl: 'http://localhost:8080',
    },
  },

  // 公開パイプライン(PostController#publish/#delete)・一括管理/環境間比較・taxonomy解決・
  // 記事プレビューのCMS依存部分をpublishing-serviceへ移設(#707/#708/#709/#712)。
  publishing: {
    input: {
      target: './openapi/publishing.json',
    },
    output: {
      ...commonOutput,
      target: './sdk/api-client/src/generated/publishing',
      baseUrl: 'http://localhost:8080',
    },
  },

  // VSCode拡張の配布・バックアップ/リストア・システム設定・ダッシュボードの状態取得を
  // platform-serviceへ移設(#693〜#696、C10)。
  platform: {
    input: {
      target: './openapi/platform.json',
    },
    output: {
      ...commonOutput,
      target: './sdk/api-client/src/generated/platform',
      baseUrl: 'http://localhost:8080',
    },
  },

  // ユーザー・ロール・権限管理をidentity-serviceへ移設(#561)。
  identity: {
    input: {
      target: './openapi/identity.json',
    },
    output: {
      ...commonOutput,
      target: './sdk/api-client/src/generated/identity',
      baseUrl: 'http://localhost:8080',
    },
  },
};
