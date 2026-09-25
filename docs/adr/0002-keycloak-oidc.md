# ADR-0002: 認証基盤に Keycloak (OIDC) を採用する

## Status

Accepted

## Context

現行の認証機構には構造的な弱点がある。`ApiKeyAuthFilter` はログイン時に発行された
`X-API-Key` を検証するだけであり、「誰が」の判定は Next.js BFF が付与する
`X-Actor-Id` / `X-Actor-Role` ヘッダを無条件に信頼している。つまりAPIキーを1つ持てば
任意の actor になりすませてしまい、認可の前提が崩れている。

また [ADR-0001](0001-domain-based-microservices.md) によりサービスがドメイン単位で
分割されるため、各サービスが個別にユーザー認証・トークン検証を実装するのは非効率かつ
セキュリティ上のリスク（実装差異によるバグ混入）が大きい。標準化されたIdP (Identity Provider)
を中心とした認証・認可基盤が必要になる。

## Decision

認証基盤として Keycloak を採用し、OIDC (OpenID Connect) ベースの認証・認可へ全面的に
切り替える。

- Web UI: Authorization Code Flow + PKCE
- VSCode拡張: Device Authorization Grant
- サービス間通信: Client Credentials Grant

`api-gateway` が全リクエストのJWT検証を担い、各サービスは OAuth2 Resource Server として
検証済みJWTのクレームに基づき宣言的に認可判定を行う。

## Consequences

**利点**

- なりすまし（actor詐称）の温床であった `X-Actor-*` ヘッダの無条件信頼を撤廃できる
- 認証ロジックをIdPに集約でき、各サービスは検証済みトークンの検証のみを行えばよい
- 標準プロトコル(OIDC)への準拠により、将来的な外部IdP連携（SSO等）の拡張が容易になる
- パスワードリセット・MFA等のセキュリティ機能をKeycloakの標準機能に委譲できる

**欠点・トレードオフ**

- Keycloakコンテナ自体の運用（可用性、バックアップ、バージョンアップ）という新たな
  運用負荷が発生する
- 既存の自前認証（ログイン、TOTP、パスワードリセット）からの移行作業が必要
- Web/VSCode拡張の両クライアントで認証フローの実装をやり直す必要がある

## Alternatives considered

**自前JWT発行の継続・改修（却下）**

現行の `X-API-Key` + `X-Actor-*` ヘッダ方式を、自前でJWTを発行する方式に改修する案。
なりすまし問題は緩和できるが、トークン発行・失効・リフレッシュ・鍵ローテーション等を
すべて自前実装する必要があり、標準化されたIdPを使うより実装・保守コストが高く、
セキュリティリスクも自前実装に起因するバグに依存し続ける。標準プロトコルに乗ることで
実績のある実装に認証の根幹を委ねる方が合理的と判断した。
