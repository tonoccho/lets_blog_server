# 07. ローカル開発手順とアーキテクチャドキュメントの更新

Issue: [#558](https://github.com/tonoccho/lets_blog_server/issues/558)

## 内容

`README.md` と `spec/phase1/00-overview.md` のアーキテクチャ図がPhase 1時点のままで、
現状(RabbitMQ/log-writer/Penpot/drawio/WordPressプロビジョニング)にも、進行中の
マイクロサービス移行(Epic #551)にも追いついていなかったため、現状と目標の両方を
反映したドキュメントへ更新した。

## 変更内容

- `README.md` に「アーキテクチャ」セクションを新設し、現状構成図と目標構成図
  (mermaid)を並記。`docs/adr/`・`spec/phase17/`への導線を追加
- `spec/phase17/`(本ディレクトリ)を新設し、Phase 17の実行計画を既存の
  `spec/phaseN/` 書式に沿って記載(00-overview + タスクごとのファイル)
- `docs/GETTING_STARTED.md` — 起動サービス一覧の更新(Ollama記載の削除、
  実際の19コンテナ構成への修正)、個別サービスの再起動・ログの見方セクションを追加
- `docs/setup.md` — 起動サービス一覧の更新、個別サービスの再起動・再ビルド手順、
  サービス間疎通確認コマンドを追加
- `docs/COMPREHENSIVE_TROUBLESHOOTING.md` — 「Multi-Service Startup Failures」節を
  新設し、依存サービス未起動(mysql/rabbitmqのhealthcheck待ち)・ポート競合
  (Penpotの9001/1080等)の典型的な失敗パターンと診断手順を追加

## 検証

- 追加した mermaid 図・リンクが実際のファイルパス・ADR番号と一致することを確認
- 新設した `docs/COMPREHENSIVE_TROUBLESHOOTING.md` の見出しアンカーが
  `docs/GETTING_STARTED.md` からのリンクと一致することを確認
