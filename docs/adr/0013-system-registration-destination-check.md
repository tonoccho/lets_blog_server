# ADR-0013: システム全体のプラグインの登録の接続先は、禁止アドレスだけを拒否し、docker ネットワーク内のアドレスは通す

- **日付**: 2026-10-16
- **関連**: #1807(本 ADR)、#1755(Epic)、#1808(プロジェクト単位の上書き)、#1762(同じ PC の経路 `lbx-shared`)、#1783(ポータルの URL の検査)、#1774(Penpot の検査)、
  #1547 / #1518(`ConnectionDestinationGuard`)
- **改訂するもの**: [docs/plugins/CONTRACTS.md](../plugins/CONTRACTS.md) §4.3 の「システム全体の登録も同じ検査を通す(例外を作らない)」。
  [ADR-0011](0011-external-service-plugin-boundary.md) のほかの決定は変えない

## 状態 (Status)

採用(Accepted)。2026-10-16。

## 背景 (Context)

[CONTRACTS.md](../plugins/CONTRACTS.md) §4.3 は、プラグインの登録の接続先を `ConnectionDestinationGuard` で検査し、システム全体の登録も
同じ検査を通すと決めていた。この検査は、自プロセスが参加する docker ネットワークの範囲と、禁止アドレス(ループバック・未指定・リンクローカル等)の
どちらも拒否する。ところが同じ PC の extensions(#1762)の `lbx-*` は、LBS が参加する共有ネットワーク `lbx-shared` 上のアドレスに解決される。
同じ検査を通すと、同じ PC の extensions を登録できない。

現行のコードは、管理者が設定するシステム設定では docker ネットワークを拒否しない扱いで揃っている
(`ExtensionsPortalUrlPolicy`(#1783)、`GitLabReachabilityChecker` の注記、Ollama / ComfyUI の URL 形式検査)。
拒否しているのはプロジェクト単位の上書き(プロジェクトの管理者が指す任意の宛先)だけである。

## 決定 (Decision)

利用者の決定(2026-10-16。Open Question 1 の既定案)。

1. **システム全体の登録の保存時検査**: 禁止アドレスだけを拒否する。
   - `http` / `https` の URL であること。
   - ホストを名前解決できること。
   - 解決した**全**アドレスが `DestinationAddressRules.isDeniedAddress`(ループバック・未指定・リンクローカル 169.254/16・IPv6 リンクローカル・`fd00:ec2::254`)でないこと。
   - **docker ネットワーク内のアドレスは拒否しない**(同じ PC の `lbx-*`、LAN のプライベートアドレスを通す)。
   - `drawioUrl`(§3.4)は、サーバーが接続せず利用者のブラウザ / VS Code が開く URL なので、URL 形式(`http` / `https`・ホストあり)だけを検査する。
2. **プロジェクト単位の上書き**は変えない。引き続き `ConnectionDestinationGuard` 全体(docker ネットワークも拒否)を通す。
   プロジェクトの管理者は、lets_blog の内部ネットワークへ接続させてはならない相手である。
3. **接続時の検査**(CONTRACTS.md §0)にも同じ規則を使う。接続のたびにホストを名前解決し、解決した全アドレスを検査し、
   **検査したアドレスへ固定して接続する**(`PinnedHttpClients`。DNS rebinding の防止、#1547)。システム全体の登録の接続では、
   検査の対象から docker ネットワークの範囲だけを外す。固定は維持する。
4. **実装の置き場**: platform-service の `SystemDestinationPolicy`(`ExtensionsPortalUrlPolicy` の一般化)。`ExtensionsPortalUrlPolicy` は同じ規則へ委ねる。
   接続時の検査を行う各サービス(#1808 以降の子 Issue)は、同じ規則を共通の部品として使う。
5. CONTRACTS.md §4.3 は、この ADR を指す記述に直す。

## 影響 (Consequences)

得るもの:

- 同じ PC の extensions(`lbx-ollama` 等)を、システム全体の登録として保存できる。
- 現行の Ollama / ComfyUI / GitLab / ポータルの扱いと、登録の規則が揃う。例外の名前の一覧(`BUNDLED_NAMES`)を増やさずに済む。

失うもの・負担:

- 管理者は、docker ネットワーク内の任意のサービス(同じネットワーク上の他のコンテナ)を接続先として保存できる。SSRF の防御は、
  禁止アドレスの拒否とアドレスの固定に縮まる。信頼の前提は「システム全体の登録を変えられるのは管理者だけ」であり、
  この前提は `requireAdmin` で守る(プロジェクトの管理者には開かない)。
- システム全体の登録と、プロジェクト単位の上書きとで検査の規則が異なる。利用者に「なぜ同じ URL が片方では保存できないのか」を説明できるよう、
  拒否の理由を画面に出す。

## 検討した代替案 (Alternatives considered)

| 案 | 却下の理由 |
| --- | --- |
| **`ConnectionDestinationGuard` 全体を通す(§4.3 の元の決定)** | 同じ PC の `lbx-*`(`lbx-shared` 上)を登録できない |
| **`lbx-*` の名前だけを例外として許す(`BUNDLED_NAMES` と同じ仕組み)** | 同梱コンテナが無く(§4.3)、名前の例外を新設すると、extensions が増えるたびに一覧を直す。現行のシステム設定の扱いとも揃わない。利用者が既定案を選んだ |
