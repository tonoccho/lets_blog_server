# Phase 15-04: VSCode拡張機能のダウンロード配布

## スコープ

Web管理画面(`/system`)からVSCode拡張機能(.vsix)をダウンロードできるようにする。APIサーバー側でオンデマンドビルドし、ビルド済みファイルをバージョン単位でキャッシュする。

## 決定事項

| 項目 | 決定内容 |
|---|---|
| ビルド方式 | APIサーバー(`api`コンテナ)からnpm/vsceを呼び出すオンデマンドビルド。事前ビルド済みファイルの配布は採用しない |
| ソース取得方法 | `docker-compose.yml`の`api`サービスに`./extension:/app/extension-src:ro`の読み取り専用バインドマウントを追加。Dockerビルドコンテキスト自体は変更せず(`./api`のまま)、既存Dockerfileへの影響を最小化 |
| ビルドツール | `api/Dockerfile`のランタイムステージに`nodejs`/`npm`(apt、Ubuntu 26.04ベースでNode 22系)を追加。`extension/package.json`に`@vscode/vsce`をdevDependencyとして追加 |
| キャッシュ | `extension/package.json`のversionをキーに`/app/extension-build/output/letsblog-vscode-<version>.vsix`へ出力。既存ファイルがあればビルドをスキップ |
| 同時実行制御 | `ReentrantLock`でビルド処理を直列化(複数ユーザーが同時にダウンロードボタンを押した場合の重複ビルド防止) |
| 認可 | `GET /api/system/vscode-extension`はAPIキーのみで呼び出し可(actor不問)。Web側は`requireSession()`でログイン必須化 |

## 実装

### バックエンド

- `VscodeExtensionBuildService`(新規): `SshKeyGenerationService`と同様、`ProcessBuilder`でホストコマンド(`npm ci` → `npm run compile` → `npx vsce package --allow-missing-repository`)に委譲。ソースを書き込み可能な作業ディレクトリへコピーしてから実行し、完了後に作業ディレクトリを削除
- `VscodeExtensionController`(新規): `GET /api/system/vscode-extension`で`.vsix`を`Content-Disposition: attachment`で返す
- `application.yml`: `app.vscode-extension-source-path`(`/app/extension-src`) / `app.vscode-extension-build-path`(`/app/extension-build`)を追加

### Web管理画面

- `web/src/lib/apiClient.ts`: `downloadVscodeExtension()`でバイナリをArrayBufferとして取得
- `web/src/app/api/vscode-extension/route.ts`(新規Route Handler): `requireSession()`後にバイナリをストリーミング返却
- `web/src/app/system/page.tsx`: 「VSCode拡張機能」カードにダウンロードリンク(`<a href="/api/vscode-extension" download>`)を追加

## テスト・実機検証

- `VscodeExtensionBuildServiceTest`: キャッシュヒット時にビルドを実行せず返すこと、ソース(`package.json`)未検出時・version未記載時に例外となることを検証(実際のnpm/vsce呼び出しはビルド環境依存のため単体テスト対象外)
- `VscodeExtensionControllerTest`: レスポンスヘッダー(Content-Disposition)を検証
- **実機検証済み**: `docker compose build api && docker compose up -d api`でイメージ再作成 → `GET /api/system/vscode-extension`を`curl`で実行し、初回リクエストで実際に`.vsix`(555ファイル、約1.1MB)が生成されることを確認。2回目のリクエストはキャッシュヒットで即時応答(0.014秒)することも確認済み
- Web管理画面の「ダウンロード」ボタンをブラウザで実際にクリックする操作確認は、本セッションではブラウザ操作ツールが使えず未実施(Route Handlerの実装・`next build`通過は確認済み)

## 既知の制限

- vsceの既定動作により、`.vsix`には`devDependencies`(TypeScriptの型定義ファイル等)の一部が含まれる(`.vscodeignore`未整備)。社内配布用途のため実害は小さいと判断し、最適化は見送り
