# 05. サイト登録時のプロビジョニング可視化

## 目的

「サイトを登録する際、サーバー内で何かプロビジョニングされているのか」という利用者からの疑問に応え、現状の挙動を利用者に明示し、必要な範囲で疎通確認を追加する。

## 現状調査結果

- Controller: [`SiteController.java`](../../api/src/main/java/com/letsblog/api/controller/SiteController.java)(`POST /api/sites`, `GET /api/sites`)
- Service: [`SiteService.java`](../../api/src/main/java/com/letsblog/api/service/SiteService.java)
- 現在の処理: 必須クレデンシャル(WordPress: baseUrl/username/appPassword、microCMS: serviceId/apiKey等)のバリデーション → 認証情報をJSON化し `CredentialCipher` で暗号化 → `Site` エンティティとしてDB保存するのみ。
- **外部CMS側やサーバー内で実際に何かを作成する「プロビジョニング」処理は行っていない**。疎通確認(ping)すら行わず、ユーザーが入力した既存の認証情報をメタデータとして暗号化保存するだけ。
- Web側: [`SiteForm.tsx`](../../web/src/app/sites/SiteForm.tsx) は登録成功時に「登録しました。」と表示するのみで、プロビジョニング状況を伝える仕組みは無い(そもそも非同期のプロビジョニング処理自体が存在しない)。

## 前提・決定事項(要確認)

- 本Phaseでは「実際にCMS側へリソースを新規作成する」ような真のプロビジョニング機能は実装しない(スコープ外)。あくまで現状の「メタデータ登録のみ」という挙動を利用者に正しく伝えること、および入力された認証情報が実際に有効かを確認する疎通確認の追加が目的。
- 疎通確認を同期的に行い失敗時は登録を拒否するか、疎通確認は行うが失敗しても登録自体は許可する(警告表示のみ)かは未決事項。

## 対応方針

1. UI上に「サイト登録は既存のWordPress/microCMSサイトの認証情報を保存するだけであり、サーバー側で新規にサイトやリソースを作成するものではありません」という趣旨の説明文を、サイト登録フォーム([`SiteForm.tsx`](../../web/src/app/sites/SiteForm.tsx))に追加する。
2. `SiteService` に登録時の疎通確認(WordPressなら該当REST APIへの軽いGETリクエスト、microCMSなら該当エンドポイントへのAPIキー検証リクエスト等)を追加し、成功/失敗をレスポンスに含める。
3. Web側は疎通確認の結果(成功/失敗)を登録成功メッセージと合わせて表示する。

## タスクチェックリスト

- [ ] 疎通確認失敗時の挙動(登録拒否 vs 警告表示のみ)をユーザーと確定する
- [ ] `SiteService` に WordPress/microCMS 向けの疎通確認処理を追加(既存の `CredentialCipher` 復号・アダプタ呼び出しロジックを流用)
- [ ] `SiteController` のレスポンスに疎通確認結果を含める
- [ ] `SiteForm.tsx` に「プロビジョニングは行わない」旨の説明文を追加
- [ ] `SiteForm.tsx` に疎通確認結果の表示を追加
- [ ] テスト整備(疎通確認成功/失敗ケースの `SiteServiceTest`)

## 未決事項

- 疎通確認失敗時に登録自体を拒否するか、警告のみで登録は許可するか
- 疎通確認のタイムアウト・リトライ方針
- 将来的に「真のプロビジョニング」(新規サイト作成の自動化)を求める要望が出た場合の扱い(Phase5以降で別途検討)
