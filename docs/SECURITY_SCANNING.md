# セキュリティスキャン

## 現状: 自動スキャンは動いていない

**このリポジトリには CI が無く、自動のセキュリティスキャンも動いていない。**

GitHub Actions は移行前から意図的に無効化されており、2026-09-03 の GitLab CE 移行時に
「CI は稼働させない」と決定した（Runner を運用しないため。#1027）。それに伴い、
かつて GitHub Actions のワークフローとして動いていた以下は、その定義ごと**すべて削除された**。

| かつて動いていたもの | 現在 |
| --- | --- |
| Dependabot（依存の脆弱性検知と自動 PR） | 削除。GitLab では動かない |
| CodeQL（Java / JS-TS の静的解析） | 削除 |
| npm audit のワークフロー | 削除。手元で実行する |
| Gradle dependency-check のワークフロー | 削除。手元で実行する |
| GitHub Security Alerts / Code scanning タブ | 移行元にしか無い |

この文書を削除せず残しているのは、**「何も無い」ことを明示するため**である。過去の記述が
消えているだけだと、どこかで動いていると誤解される。

---

## 手元で実行する

自動化されていない以上、意識して回す必要がある。**依存を追加・更新したとき**と、
**定期的に**（月1回程度を目安に）実行する。

### 依存の脆弱性

```bash
cd apps/web       && npm audit --audit-level=moderate
cd apps/extension && npm audit --audit-level=moderate
./gradlew dependencyCheckAnalyze
```

Gradle 側の抑制設定は `config/dependency-check-suppression.xml`。誤検知を抑制するときは
**理由をコメントで書く**こと。抑制の理由が残っていないと、次に見た人が正しさを判断できない。

### 静的解析

CodeQL の代替は置いていない。JVM 側は Gradle のビルドに含まれる静的解析、フロントエンドは
ESLint（`cd apps/web && npm run lint`）が最低限の役割を担う。

これで CodeQL と同等の網羅性は得られない。**それは受け入れた上でのトレードオフ**であり、
CI を持たない判断（#1027）の帰結である。将来 CI を導入するなら、GitLab SAST か
semgrep をジョブとして置くのが素直な選択肢になる（CE で使える範囲の確認が要る）。

---

## 脆弱性を見つけたら

報告手順は [SECURITY.md](../SECURITY.md) を参照。**公開の Issue には書かない。**

---

## 代わりに何が品質を担保しているか

セキュリティスキャンとは別軸だが、コードの健全性はコミットとマージの経路上で機械的に
強制されている。詳細は [README の「品質の担保」](../README.md#品質の担保) を参照。

| 仕組み | 実体 |
| --- | --- |
| git フック | `scripts/git-hooks/pre-commit` |
| Claude Code フック | `.claude/hooks/guard.py` |
| カバレッジゲート | `scripts/check-changed-coverage.py` |

---

## 関連

- [SECURITY.md](../SECURITY.md) — 脆弱性の報告手順
- [DEPENDENCY_UPDATE_POLICY.md](DEPENDENCY_UPDATE_POLICY.md) — 依存更新の運用
- [GITLAB_WORKFLOW_SETUP.md](GITLAB_WORKFLOW_SETUP.md) — ワークフロー環境の構築
