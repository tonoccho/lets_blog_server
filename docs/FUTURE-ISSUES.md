# 今後のタスク・GitHub Issues

> **前提**: このリポジトリに CI は無い（2026-09-03、GitLab CE 移行時の決定。#1027）。
> 以下で CI に触れる項目は、CI を持つ判断に変わった場合の案である。

このドキュメントは、Let's Blog Server の今後の実装タスクを優先度別に整理したものです。

---

## Priority 1: すぐに実装（Blocker）

### ✅ 1. React コンポーネント実装

**期間:** 40時間  
**依存:** Design System PR (#3)

実装対象:
- 基本コンポーネント: Button, Input, Textarea, Select, Checkbox, Radio, Toggle
- 複合コンポーネント: Card, Modal, Dropdown, Tabs, Accordion
- レイアウト: Header, Sidebar, Footer, Container

チェックリスト:
- [ ] 各コンポーネント .tsx 実装
- [ ] Tailwind CSS スタイリング
- [ ] TypeScript 型定義
- [ ] Props インターフェース設計
- [ ] ダークモード対応
- [ ] アクセシビリティ（WCAG AA）対応

---

### ✅ 2. Next.js ページ実装

**期間:** 30時間  
**依存:** #1（コンポーネント実装）

実装対象:
- **認証フロー:** ログイン、サインアップ、パスワードリセット
- **ダッシュボード:** メイン、分析ページ
- **サイト管理:** 一覧、設定
- **投稿管理:** 一覧、編集
- **ユーザー管理:** 一覧、権限設定（Admin用）
- **システム設定:** 一般、メール、セキュリティ、ストレージ（Admin用）

チェックリスト:
- [ ] 各ページコンポーネント作成
- [ ] API 連携（data fetching）
- [ ] フォーム送信・バリデーション
- [ ] エラーハンドリング
- [ ] Loading/Skeleton 状態
- [ ] レスポンシブ対応
- [ ] SEO（meta tags）

---

### ✅ 3. Design Tokens 生成スクリプト実装

**期間:** 8時間

スクリプト作成:
- [ ] `scripts/generate-design-tokens.js` 実装
- [ ] CSS 変数ファイル生成
- [ ] Tailwind config 生成
- [ ] React constants 生成
- [ ] TypeScript 型定義生成
- [ ] npm scripts 追加（tokens:generate, tokens:watch）

---

### ✅ 4. Docker Compose デバッグ・検証

**期間:** 4時間

検証項目:
- [ ] 全サービス起動テスト
- [ ] ヘルスチェック実装
- [ ] ネットワーク接続確認
- [ ] ボリューム マウント確認
- [ ] ログ出力確認

---

## Priority 2: 短期（1-2週間）

### 5. エンドツーエンド テスト（Playwright）

**期間:** 20時間

テスト対象:
- ログインフロー
- ダッシュボード表示
- サイト管理操作
- 投稿編集・保存
- Penpot プラグイン機能

実装:
- [ ] Playwright セットアップ
- [ ] テストスイート作成
- [ ] CI/CD 統合
- [ ] カバレッジレポート

---

### 6. ユニットテスト（Jest）

**期間:** 12時間

テスト対象:
- コンポーネント Unit テスト
- ユーティリティ関数テスト
- フック テスト
- API クライアント テスト

実装:
- [ ] テスト環境セットアップ
- [ ] テストスイート作成
- [ ] Coverage ≥ 80%
- [ ] CI/CD 統合

---

### 7. Storybook セットアップ

**期間:** 8時間

目的: コンポーネント カタログ・ドキュメント

実装:
- [ ] Storybook インストール
- [ ] 各コンポーネントの Story 作成
- [ ] Tailwind CSS 対応
- [ ] ダークモード サポート
- [ ] Addon 設定（a11y, viewport等）
- [ ] Deploy to Vercel / Netlify

---

### 8. CI/CD パイプライン

**期間:** 12時間

パイプライン:
- [ ] lint + format チェック
- [ ] TypeScript type check
- [ ] Unit テスト実行
- [ ] Build 検証
- [ ] Bundle size チェック
- [ ] E2E テスト実行（Playwright）
- [ ] Deploy to staging

---

## Priority 3: 中期（1-2ヶ月）

### 9. Docker Compose 本番構成

**期間:** 6時間

タスク:
- [ ] docker-compose.prod.yml 作成
- [ ] SSL/TLS 証明書設定（Let's Encrypt）
- [ ] バックアップ戦略 実装
- [ ] ロードバランサー設定
- [ ] ログ集約（ELK / Loki）
- [ ] モニタリング（Prometheus / Grafana）

---

### 10. Penpot ↔ GitHub 自動同期

**期間:** 20時間

機能:
- Penpot ファイル変更を GitHub にコミット
- Design System ドキュメント自動更新
- コンポーネント情報を JSON にエクスポート
- CI で design-tokens.json を自動生成

実装:
- [ ] Webhook 設定
- [ ] ファイル監視・変換スクリプト
- [ ] Git コミット自動化
- [ ] 競合解決ロジック

---

### 11. Internationalization（i18n）

**期間:** 16時間

対応言語:
- 日本語（デフォルト）
- English
- 中文（簡体字）

実装:
- [ ] i18n ライブラリ設定（next-i18next等）
- [ ] 言語ファイル作成（JSON）
- [ ] UI テキスト 翻訳
- [ ] RTL 言語対応（将来）

---

## Priority 4: 長期（3ヶ月+）

### 12. VSCode 拡張実装

**期間:** 40時間  
**説明:** Let's Blog AI アシスタント VSCode 拡張

機能:
- [ ] Quick Pick: サイト選択
- [ ] AI コマンド実行
- [ ] 出力パネル
- [ ] 設定 UI
- [ ] Markdown エディタ統合

---

### 13. Design Tokens 自動生成 CI

**期間:** 12時間

機能:
- Penpot から Design Tokens JSON を自動抽出
- CI で自動生成
- 色・タイポ・スペーシング等を自動更新
- PR 自動作成（マージ前に確認）

実装:
- [ ] Penpot API 連携
- [ ] JSON 生成スクリプト
- [ ] CI ワークフロー
- [ ] PR コメント自動生成

---

### 14. キャッシング戦略

**期間:** 8時間

対象:
- CDN キャッシング
- Browser キャッシング
- API キャッシング
- Redis セッション キャッシング

実装:
- [ ] Cache-Control ヘッダ設定
- [ ] ETag 生成
- [ ] Redis 設定
- [ ] キャッシュ無効化戦略

---

### 15. パフォーマンス最適化

**期間:** 12時間

対象:
- Core Web Vitals 最適化
- 画像最適化（WebP、lazy loading）
- フォント最適化
- バンドルサイズ削減
- 遅延読み込み（code splitting）

実装:
- [ ] Lighthouse スコア ≥ 95
- [ ] LCP < 2.5s
- [ ] CLS < 0.1
- [ ] FID < 100ms

---

### 16. セキュリティ強化

**期間:** 16時間

対象:
- CSRF 保護
- XSS 対策
- SQL インジェクション 対策
- CORS 設定
- レート制限
- WAF ルール

実装:
- [ ] Content Security Policy（CSP）
- [ ] Helmet.js 設定
- [ ] OWASP セキュリティ チェック
- [ ] 定期的なセキュリティ監査

---

## Priority 5: チャレンジング（高難度）

### 17. リアルタイム コラボレーション

**期間:** 60時間  
**難度:** ⭐⭐⭐⭐⭐

機能:
- 複数ユーザー同時編集
- Operational Transformation（OT）実装
- WebSocket リアルタイム更新
- コンフリクト解決

実装:
- [ ] WebSocket サーバー（Socket.io / ws）
- [ ] OT エンジン実装
- [ ] バージョン管理
- [ ] テストスイート

---

### 18. Markdown ↔ Visual エディタ同期

**期間:** 20時間  
**難度:** ⭐⭐⭐⭐

機能:
- WYSIWYG エディタ UI
- Markdown ソースビュー
- 相互変換
- Diff 表示

実装:
- [ ] Prosemirror / Slate 統合
- [ ] Markdown パーサー
- [ ] AST 変換エンジン
- [ ] ショートカット実装

---

### 19. AI 画像生成統合

**期間:** 16時間

機能:
- テキストプロンプトから画像生成
- Stable Diffusion / DALL-E 統合
- 画像ギャラリー管理
- メタデータ保存

実装:
- [ ] API 連携（OpenAI / Replicate）
- [ ] キュー管理（Bull / Celery）
- [ ] 画像キャッシング
- [ ] コスト管理

---

## 実装ロードマップ

### Phase 1（今月）✅ 完了
- [x] Penpot Docker 統合
- [x] MCP サーバー実装
- [x] Penpot プラグイン実装
- [x] デザインシステム（仕様・色・タイポ・コンポーネント・ページ・Design Tokens）
- [x] E2E 検証ガイド
- [x] ドキュメント作成

### Phase 2（1-2週間）
- [ ] React コンポーネント実装 (#1)
- [ ] Next.js ページ実装 (#2)
- [ ] Design Tokens 生成スクリプト (#3)
- [ ] Playwright E2E テスト (#5)

### Phase 3（1-2ヶ月）
- [ ] Jest ユニットテスト (#6)
- [ ] Storybook セットアップ (#7)
- [ ] CI/CD パイプライン (#8)
- [ ] Docker 本番構成 (#9)

### Phase 4（3ヶ月+）
- [ ] Penpot ↔ GitHub 自動同期 (#10)
- [ ] i18n 実装 (#11)
- [ ] VSCode 拡張 (#12)
- [ ] Design Tokens 自動生成 CI (#13)
- [ ] キャッシング・パフォーマンス最適化（#14, #15）
- [ ] リアルタイム コラボレーション（#17）

---

## 見積もり時間合計

| 優先度 | 項目数 | 総時間 | 期間 |
|--------|--------|--------|------|
| P1 | 4 | 82h | 2週間 |
| P2 | 4 | 52h | 2週間 |
| P3 | 5 | 72h | 4週間 |
| P4 | 4 | 88h | 8週間+ |
| P5 | 3 | 96h | 12週間+ |
| **合計** | **20** | **390h** | **4-5ヶ月** |

---

## ハイライト・テーマ

### セキュリティ・品質
- WCAG AA アクセシビリティ準拠
- OWASP セキュリティ要件
- ≥ 90% テストカバレッジ
- Lighthouse スコア ≥ 90

### パフォーマンス
- LCP < 2.5s
- FID < 100ms
- CLS < 0.1

### ユーザー体験
- ダークモード完全対応
- レスポンシブ（モバイル第一）
- AI アシスタント統合
- リアルタイム コラボレーション

---

**最終更新:** 2024-08-07  
**進捗:** Phase C（デザインシステム）✅ 完了 / 次: Phase D-3（GitHub Issues 作成）
