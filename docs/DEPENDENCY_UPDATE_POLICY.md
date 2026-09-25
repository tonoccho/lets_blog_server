# 依存更新の運用

## 現状: 自動更新は動いていない

**Dependabot は GitLab では動かない。** 2026-09-03 の GitLab CE 移行に伴い、その設定は
削除した（#1027）。後継は置いていない。

Renovate をセルフホストすれば同等のことはできるが、それには CI かスケジュール実行の
基盤が要る。CI を稼働させない判断（#1027）と整合しないため、**当面は手動運用**とする。

なお移行前の Dependabot 設定は `updates: []` で、実質すでに何も更新していなかった。
自動更新が止まったのは移行が原因ではなく、それ以前からである。

---

## 手動での更新手順

### 実行頻度

- **依存を追加・変更したとき**（必須）
- **月1回程度**（脆弱性の確認）

### 1. 脆弱性の確認

```bash
cd apps/web       && npm audit --audit-level=moderate
cd apps/extension && npm audit --audit-level=moderate
./gradlew dependencyCheckAnalyze
```

### 2. 更新可能なものの確認

```bash
cd apps/web       && npm outdated
cd apps/extension && npm outdated
./gradlew dependencyUpdates   # プラグインが入っている場合
```

### 3. 更新の適用

**更新は Issue にする。** このリポジトリでは全ての変更が Issue から始まる
（`.claude/CLAUDE.md` → Source of Truth）。依存更新も例外ではない。

| 更新の種類 | 扱い |
| --- | --- |
| パッチ（`1.2.3` → `1.2.4`） | まとめて1つの Issue でよい |
| マイナー（`1.2.x` → `1.3.0`） | まとめてよいが、変更点を確認する |
| メジャー（`1.x` → `2.0`） | **1つずつ別の Issue**。破壊的変更の追随が要る |
| 脆弱性の修正 | 優先度 `priority::P0`。他を止めてでも入れる |

更新後は、影響範囲のテストを手元で回す。CI が受け止めてくれないため、**ここを飛ばすと
壊れたまま入る**。

```bash
./gradlew test
cd apps/web && npm run test:coverage && npm run lint && npm run build
npm run test:at            # 受け入れテスト
```

---

## `package.json` / `build.gradle` の扱い

これらは `.claude/hooks/paths.py` の分類上**中立**である。テストとプロダクションの両方が
同じファイルを共有するため、プロダクション扱いにすると「テスト専用の依存を足すテスト
フェーズのコミット」がフェーズ分離違反になり、通常の作業が成立しなくなるためである。

したがって依存更新のコミットは、テストファーストの要求を受けない。**依存の妥当性は
レビューと、上記の手動スキャンで担保する。**

---

## 将来 Renovate を入れるなら

CI を持つ判断に変わった場合、Renovate のセルフホストが現実的な選択肢になる。GitLab に
対応しており、MR を自動で作れる。その際は次を決める必要がある。

- 実行基盤（GitLab Runner のスケジュールジョブか、外部の cron か）
- 自動マージの可否。**このリポジトリのマージ規約は squash のみで、`glab mr merge` に
  `--squash` を要求するフックがある**（`.claude/CLAUDE.md` → Enforcement）。Renovate の
  自動マージがこれと整合するかの確認が要る
- カバレッジゲート（`scripts/check-changed-coverage.py`）との関係

---

## 関連

- [SECURITY_SCANNING.md](SECURITY_SCANNING.md) — セキュリティスキャンの現状
- [README の「品質の担保」](../README.md#品質の担保) — CI が無い代わりに何が守っているか
