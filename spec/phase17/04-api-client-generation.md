# 04. サービス別OpenAPI公開とorvalのマルチターゲット化

Issue: [#555](https://github.com/tonoccho/lets_blog_server/issues/555)

## 内容

サービスが分割されるとOpenAPI specも分割されるため、TypeScriptクライアント生成の仕組みを
先に多サービス対応させた。この Issue の時点ではサービスは `legacy-api` のみだが、将来の
サービス抽出Issue(Phase 19)が1エントリ追加するだけで済むようにしてある。

## 変更内容

- 各サービスはspringdocの既定パス `/v3/api-docs` でOpenAPI specを公開する規約とし、
  `legacy-api`が独自に上書きしていた `/api-docs` を撤廃
- `orval.config.js` を名前付きターゲット形式へ再構成。出力先は
  `sdk/api-client/src/generated/<service>/`
- `scripts/generate-api-client.sh` がサービスのリストをループして順に spec を取得
  (未起動サービスがあれば、そのサービス名と期待するURLを明示したエラーで停止する)
- `sdk/api-client/src/index.ts` は従来と同じシンボル名で再エクスポートするため、
  `web`/`extension` 側の `import ... from '@api-client'` は無変更

## 新規サービス追加時の手順

`CLAUDE.md` の「18. API Client Code Generation」に手順を記載。要点:
1. `orval.config.js` にターゲットを追加
2. `scripts/generate-api-client.sh` の `SERVICES` にサービスを追加
3. `sdk/api-client/src/index.ts` に re-export を追加
4. 新サービスは `springdoc.api-docs.path` を上書きしない

## 検証

- `npx orval --config orval.config.js` でlegacyApiターゲットの生成を確認
- `web`(`npm run build`)・`extension`(`npm run compile`)の型チェックが通ることを確認
