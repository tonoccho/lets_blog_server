# ADR-0009: sdk/api-client は web から利用せず、生成物の維持のみを行う

## Status

Accepted

## Context

`sdk/api-client`(`@lets-blog/api-client`)は、各サービスの OpenAPI spec から orval で
TypeScript クライアントを生成する仕組みである(#555、#739 で10サービス構成へ追随、
#809 で全サービスの再エクスポートを完了)。

導入当初の意図は「手書きのフェッチ処理を減らし、生成クライアントへ寄せる」ことだった。
#584(Web の API 呼び出しを gateway 経由へ統一する)のスコープにもその移行が含まれていたが、
調査の結果、現状の生成物は web から実利用できる状態になっていないことが判明した(#750)。

### 実態(2026-08-31 時点)

1. **消費者がいない。** `web/src` と `extension/src` のいずれにも
   `@api-client` / `@lets-blog/api-client` を import している箇所は無い
   (`web/tsconfig.json` と `extension/tsconfig.json` の path mapping だけが存在する)。
   移行の足場だった `web/src/lib/generatedApiClient.ts` は #739 の実装(PR #812)で削除済み。

2. **生成コードがベースURLをハードコードしている。** `orval.config.js` の
   `baseUrl: 'http://localhost:8080'` が生成物へ焼き込まれ、`getXxxUrl()` 系が
   絶対URLを返す。**275箇所**が `http://localhost:8080` を含む。
   このポートは docker-compose で外部公開されておらず、コンテナ内から見た `localhost` は
   呼び出し元コンテナ自身を指すため到達しない。

3. **認証を扱えない。** 生成クライアントは Keycloak の Bearer トークンを付与する仕組みを
   持たない。全バックエンドサービスは JWT を要求する(ADR-0008)ため、そのまま呼ぶと 401 になる。

4. **web 側は既に別の解を持っている。** `web/src/lib/apiClient.ts` は `import 'server-only'` で、
   `getToken`(next-auth)がセッション Cookie から取り出したアクセストークンを
   `Authorization: Bearer` として付与し、ベースURLは `apiBaseUrl.ts` の `gatewayUrl()` に
   集約されている(#584)。約150関数がこの上に載っている。

## Decision

**web からは `sdk/api-client` を利用しない。** 手書きの `web/src/lib/apiClient.ts` を
引き続き web の API 呼び出しの唯一の経路とする。

**`sdk/api-client` は生成物として維持する。** 削除はしない。OpenAPI spec は
`scripts/generate-api-client.sh` で再生成でき(手順は `docs/API_CLIENT_GENERATION.md`)、
API の形を機械可読な形で残す価値がある。#810 で型チェックも入り、生成物が壊れれば検知できる。

### ベースURLの二重管理について

web 側の組み立ては `apiBaseUrl.ts` の `gatewayUrl()` に一本化されている。
`sdk/api-client` 側は生成物に焼き込まれた絶対URLを持つが、**web はそれを使わない**ため、
実行時に二重管理になることはない。

未使用の mutator `sdk/api-client/src/apiClient.ts` が独自に
`REACT_APP_API_URL` / `NEXT_PUBLIC_API_URL` からベースURLを組み立てていた点は、
本 ADR で解消した(下記「Consequences」参照)。なお `orval.config.js` は
このファイルを mutator として指定していないため、生成コードからも参照されていない。

## Consequences

- **web の API 呼び出しは手書きのまま**である。型の追随は人力で、
  spec との乖離は自動検知されない。これは受け入れるトレードオフとする
  (150関数の書き換えは #750 の Out of Scope)。
- `sdk/api-client/src/apiClient.ts` から独自のベースURL組み立てを撤去し、
  呼び出し元がベースURLと認証ヘッダーを注入する形にした。
  未接続のファイルが「もっともらしいが到達しないURL」を持ち続けると、
  将来これを使おうとした人が原因の分かりにくい失敗を踏むため。
- 将来 web を生成クライアントへ移行する場合は、少なくとも次の3つが前提になる。
  1. `orval.config.js` の `baseUrl` を実行時に差し替えられる形にする(または相対URLにする)
  2. mutator を接続し、`Authorization` ヘッダーを注入できるようにする
  3. `web/src/lib/apiClient.ts` の呼び出し元を段階的に置き換える

  その判断は本 ADR を更新して行うこと。

## Alternatives considered

**web を生成クライアントへ移行する(却下)**

型の自動追随という利点は大きいが、上記3点の前提整備に加えて約150関数の書き換えが必要で、
#750 の Out of Scope として明示されている。ベースURLと認証という土台が未整備のまま
部分移行すると、2つの呼び出し経路が並存して認証やベースURLの扱いが分岐する。

**`sdk/api-client` を削除する(却下)**

消費者がいない以上、削除も筋は通る。しかし OpenAPI spec 自体は
`openapi/*.json` としてコミットされており、生成物はその機械可読な写像である。
#810 で型チェックが入ったことで、spec の変更が型レベルで壊れれば検知できる。
再導入するコストの方が、維持コストより大きいと判断した。
