# ADR-0012: extensions のツール(Ollama・ComfyUI・PlantUML)を lets_blog の Keycloak で認証する

- **日付**: 2026-10-15
- **関連**: #1803(本 ADR)、#1755(Epic)、#1794(Epic)、#1804(lets_blog 側の実装)、`seiji/lets_blog_server_extensions` の #23〜#25(extensions 側の実装)、
  #1805(extensions を OIDC クライアントとして登録)
- **改訂するもの**: [ADR-0011](0011-external-service-plugin-boundary.md) と [docs/plugins/CONTRACTS.md](../plugins/CONTRACTS.md) の §0(認証)・§4.2(認証情報の単位)。
  ADR-0011 のほかの決定は変えない
- **関連 ADR**: [ADR-0002](0002-keycloak-oidc.md)(Keycloak を使う理由)、[ADR-0005](0005-service-to-service-client-credentials.md)(サービス間の client credentials)

## 状態 (Status)

採用(Accepted)。2026-10-15。

## 背景 (Context)

[CONTRACTS.md](../plugins/CONTRACTS.md) §4.2 は「システム全体の登録は認証情報を持たない(LAN 内で届く前提)」と決めており、冒頭は
「子 Issue で変える場合は新しい ADR を作る」としている。LAN の外(別ホストの extensions)へ API 呼び出しが出ていく構成も対象になり、
この前提が成り立たなくなった。このままでは、lets_blog 側(#1804)と extensions 側(seiji/lets_blog_server_extensions#23)が別々の前提で作られる。

## 決定 (Decision)

利用者の決定(2026-10-15)。

1. **使う Keycloak**: lets_blog の Keycloak、realm `letsblog`。extensions のサービスを OIDC クライアントとして登録する。
2. **認証の対象**: lets_blog から Ollama・ComfyUI・PlantUML への API 呼び出しを、Keycloak のトークン(client credentials)で認証する。
3. **API 呼び出しのクライアント**: 専用のクライアントを作る。**`letsblog-services` は流用しない。** `letsblog-services` は
   `realm-management` のロールを持つ(realm の管理 API を呼べる)ので、これでツールへ呼び出すと、ツールの運用者が受け取ったトークンで
   lets_blog の realm を操作できてしまう。専用のクライアントは、ロールを持たない。
4. **トークンの約束**(推奨値。変えるなら #1804 と seiji/lets_blog_server_extensions#23 もそろえる):

   | 項目 | 値 |
   | --- | --- |
   | クライアント(`azp`) | `letsblog-tools-client`(confidential、service account のみ。`serviceAccountsEnabled=true`、ほかのフローは無効) |
   | `aud` | `lbx-tools`(audience マッパーで載せる) |
   | `iss` | lets_blog の公開 URL + `/auth/realms/letsblog`(同じ PC では `KC_HOSTNAME` で固定された `https://localhost/auth/realms/letsblog`) |
   | lets_blog の環境変数 | `KEYCLOAK_TOOLS_CLIENT_SECRET` |
   | 署名の鍵(JWKS)の取得先 | lets_blog の公開 URL の `/auth/realms/letsblog/protocol/openid-connect/certs`。同じ PC の extensions は、`lbx-shared` の内部 URL `http://lbs-keycloak:8080/auth/...` でもよい |

   extensions は `iss`・`aud`・署名・有効期限を検証し、`azp` が `letsblog-tools-client` であることを確かめる。別ホストの extensions は、
   JWKS を lets_blog の公開 URL から取る。
5. **認証の対象と対象外**:
   - 対象: Ollama・ComfyUI・PlantUML への API 呼び出し(上の 1〜4)。
   - 対象外: **GitLab API。** lets_blog から GitLab API を呼ぶときは、アクセストークン(#1794)のままにする。
   - GitLab と Penpot は、それぞれが持つ OIDC ログインで守る(#1805 のクライアント `lbx-gitlab`・`lbx-penpot`)。proxy では守らない。
6. **内部経路**: proxy が別名 `lbx-ollama`・`lbx-comfyui`・`lbx-plantuml`・`lbx-portal` を引き受ける。ツール(Ollama・ComfyUI・PlantUML)は
   `lbx-shared` から外す。`lbx-gitlab` と Penpot はそのままにする。
7. **対象の構成**:
   - API(client credentials)は、別ホストの extensions も対象にする。
   - ブラウザの SSO は、同じ PC だけを対象にする。別ホストのブラウザ SSO は後続の Issue にする。
8. **移行**: 両側とも任意で有効にし、**既定は無効**。
   - lets_blog は、`KEYCLOAK_TOOLS_CLIENT_SECRET` を設定したときだけトークンを付ける。設定したのに取れなければ、理由の分かるエラーにする(黙って認証なしへ落とさない)。
   - extensions は、`.env` で有効にしたときだけ検証する。
9. **トークンの送り先**: システム設定(と `PLANTUML_BASE_URL`)の宛先にだけ送る。**プロジェクト単位の上書き先には送らない**
   (プロジェクトの管理者が指す任意の宛先へ、lets_blog 全体の資格情報を渡さないため)。
10. **ロール**: ポータルと ComfyUI の画面は `admin` だけにする。GitLab と Penpot は、ログインできる lets_blog のアカウント全員とする。

## 影響 (Consequences)

得るもの:

- LAN の外にある extensions のツールも、認証つきで呼べる。lets_blog の realm の権限を、ツールの運用者に渡さない。
- 既定が無効なので、既存の環境は何も変えなくても動く。両側を別々に有効にでき、順序の制約が無い。

失うもの・負担:

- 鍵(`KEYCLOAK_TOOLS_CLIENT_SECRET`)の管理と、Keycloak のクライアントの追加(新規構築は realm import、既存は kcadm)が要る。
- トークン取得の失敗という、新しい失敗の面が増える(Keycloak に届かない・secret の不一致)。
- プロジェクト単位の上書き先は認証されない。認証が要るなら、その宛先ごとの認証情報(§4.2 の既存の規則)で足す。
- 別ホストのブラウザ SSO は未対応のまま残る(後続の Issue)。

## 検討した代替案 (Alternatives considered)

| 案 | 却下の理由 |
| --- | --- |
| **認証しない(LAN 内の前提のまま)** | 別ホストの extensions を対象にすると、前提が成り立たない |
| **`letsblog-services` を流用する** | `realm-management` のロールを持つ。ツール側へ渡るトークンが、realm の管理に使えてしまう |
| **静的な API キーを lets_blog のシステム設定に持つ** | 有効期限と失効の仕組みを自前で持つことになる。Keycloak の鍵のローテーションと検証の仕組みを使えない |
| **常に有効にする(任意にしない)** | 既存の環境が、両側を同時に更新するまで動かなくなる |
| **トークンをプロジェクト単位の上書き先にも送る** | プロジェクトの管理者が、任意の宛先へ lets_blog の資格情報を送らせられる |
| **GitLab API も Keycloak のトークンにする** | GitLab のアクセストークン(#1794)は、リポジトリごとの権限を表せる。利用者が、アクセストークンのままにすると決めた |
