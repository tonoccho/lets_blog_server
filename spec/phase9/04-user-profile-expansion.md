# 04. ユーザープロフィール拡充

## 目的

サーバー側ユーザー情報(プロフィール)をさらに拡張し、以下の 3 点に対応する:

1. **SNS リンク(14 種固定)**: Facebook, YouTube, WhatsApp, TikTok, Instagram, WeChat, X(Twitter), Threads, GitHub, Pinterest, Meetup, LINE, LinkedIn, はてなへのリンク URL を個別に設定可能に
2. **カスタムリンク(任意個数)**: ユーザー独自の「ラベル + URL」ペアを好きなだけ追加できるリンク集機能
3. **WordPress 互換プロフィール項目の改善**: DisplayName を自動候補から選択可能に(WordPress 本来の UX に合わせる)、Email を表示のみに

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| SNS リンク実装方式 | `users.social_links` JSON 列(Hibernate `@JdbcTypeCode(SqlTypes.JSON)`、14 フィールドの固定 record 型) |
| SNS 固定キー | facebook, youtube, whatsapp, tiktok, instagram, wechat, x, threads, github, pinterest, meetup, line, linkedin, hatena(計 14) |
| SNS マルチバイト対応 | URL のみ格納するため不要 |
| カスタムリンク実装方式 | `users.custom_links` JSON 列、`List<CustomLink>` (label + url) を JSON 配列で格納(子テーブル化は過剰) |
| カスタムリンク並べ替え | ドラッグ&ドロップ不要。JSON 配列の要素順 = 表示順 |
| DisplayName 生成ロジック | nickname / firstName / lastName / `${firstName} ${lastName}` / `${lastName} ${firstName}` / email localpart から動的候補生成、プルダウンで選択 |
| DisplayName の永続性 | 従来通り `users.display_name` に選択結果を格納(DB 列追加不要) |
| Email 編集機能 | 表示のみ(編集不可)。既存の別エンドポイント(スコープ外)での変更に委譲 |
| Email の表示位置 | フォーム冒頭に display-only フィールドとして表示 |
| マイグレーション | `V15__add_user_social_and_custom_links.sql`(既存 V14 の次、social_links + custom_links 同時追加) |

## アーキテクチャ・実装詳細

### データベース層

#### Migration: `V15__add_user_social_and_custom_links.sql`

```sql
ALTER TABLE users ADD COLUMN social_links JSON NULL;
ALTER TABLE users ADD COLUMN custom_links JSON NULL;
```

- 両列共に `NULL` 許可(既存ユーザーは `NULL` で初期化)
- JSON 列が MySQL 5.7.8+ / PostgreSQL 9.3+ で対応

### バックエンド

#### 新規ドメインモデル

`SocialLinks.java`(新規 record):
```java
package com.letsblog.api.domain;

public record SocialLinks(
    String facebook,
    String youtube,
    String whatsapp,
    String tiktok,
    String instagram,
    String wechat,
    String x,
    String threads,
    String github,
    String pinterest,
    String meetup,
    String line,
    String linkedin,
    String hatena
) {}
```

すべてのフィールド nullable(未設定は null)。

`CustomLink.java`(新規 record):
```java
package com.letsblog.api.domain;

public record CustomLink(
    String label,
    String url
) {}
```

#### `User.java` 拡張

```java
@Entity
public class User {
    ...既存フィールド...

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "social_links", columnDefinition = "json")
    private SocialLinks socialLinks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_links", columnDefinition = "json")
    private List<CustomLink> customLinks;  // JSON 配列
}
```

#### DTO 拡張

`UserProfileResponse.java` / `UserProfileUpdateRequest.java`:
```java
public record UserProfileResponse(
    Long id,
    String email,  // 新規、表示用
    String firstName,
    String lastName,
    String displayName,
    String nickname,
    String websiteUrl,
    String bio,
    String locale,
    String avatarUrl,
    String department,
    String position,
    SocialLinks socialLinks,    // 新規
    List<CustomLink> customLinks,  // 新規
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) { }
```

#### `UserService.updateUserProfile()` 処理

現状(確認済み): 各フィールドをリクエストから直接上書き。

修正: `socialLinks` / `customLinks` も同じく上書き(部分更新ではなく全体置換)。フロント側は毎回全フィールドを送信するため問題なし。

### フロントエンド

#### Email 表示欄の追加

`UserProfileForm.tsx`(line TBD, 現状確認済み line 9-121):

```tsx
export function UserProfileForm({ profile }: { profile: UserProfile }) {
  // ...既存のformAction setup...
  return (
    <form action={formAction}>
      {/* 新規: Email 表示欄(編集不可) */}
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600">メールアドレス</span>
        <input
          type="email"
          value={profile.email ?? ""}
          disabled
          className="rounded border border-neutral-300 px-3 py-2 text-sm bg-neutral-50 text-neutral-500"
        />
        <span className="text-xs text-neutral-400">（変更は設定から行えます）</span>
      </label>

      {/* 既存フィールド: 姓/名/表示名/ニックネーム/ウェブサイト/言語/部署/役職 */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        ...既存フィールド...
      </div>

      {/* 新規: DisplayName プルダウン化 */}
      <DisplayNameSelect
        firstName={/* 同じformの input 値を読む */}
        lastName={/* */}
        nickname={/* */}
        email={profile.email}
        defaultValue={profile.displayName}
      />

      {/* 既存フィールド: 自己紹介/アバターURL */}
      ...

      {/* 新規セクション: SNS リンク */}
      <fieldset>
        <legend className="text-sm font-medium text-neutral-600">SNS リンク</legend>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <SocialLinkField label="Facebook" name="facebook" value={profile.socialLinks?.facebook} />
          <SocialLinkField label="YouTube" name="youtube" value={profile.socialLinks?.youtube} />
          {/* ... 全14種 */}
        </div>
      </fieldset>

      {/* 新規セクション: カスタムリンク */}
      <CustomLinksEditor defaultLinks={profile.customLinks} />

      {/* 既存: エラー・成功メッセージ・保存ボタン */}
      ...
    </form>
  );
}

function SocialLinkField({ label, name, value }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-neutral-600">{label}</span>
      <input
        name={`socialLinks.${name}`}  // 送信時: socialLinks オブジェクトに集約
        type="url"
        defaultValue={value ?? ""}
        className="rounded border border-neutral-300 px-3 py-2 text-sm"
      />
    </label>
  );
}
```

#### DisplayName プルダウン化

新規コンポーネント `DisplayNameSelect.tsx`:
```tsx
"use client";
import { useMemo, useState } from "react";

export function DisplayNameSelect({
  firstName,
  lastName,
  nickname,
  email,
  defaultValue
}: { ... }) {
  // フォーム内の入力値が変わるたびに候補を再計算
  const candidates = useMemo(() => {
    const list: string[] = [];
    
    if (nickname) list.push(nickname);
    if (firstName) list.push(firstName);
    if (lastName) list.push(lastName);
    if (firstName && lastName) {
      list.push(`${firstName} ${lastName}`);
      list.push(`${lastName} ${firstName}`);
    }
    if (email) {
      list.push(email.split('@')[0]);  // emailローカルパート
    }
    
    // 重複除去
    const unique = Array.from(new Set(list));
    
    // 現在保存済み値が候補に無ければ追加(データロス防止)
    if (defaultValue && !unique.includes(defaultValue)) {
      unique.unshift(defaultValue);  // 先頭に
    }
    
    return unique;
  }, [firstName, lastName, nickname, email]);

  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-neutral-600">表示名</span>
      <select
        name="displayName"
        defaultValue={defaultValue ?? ""}
        className="rounded border border-neutral-300 px-3 py-2 text-sm"
      >
        <option value="">選択してください</option>
        {candidates.map(c => <option key={c} value={c}>{c}</option>)}
      </select>
    </label>
  );
}
```

#### カスタムリンク編集コンポーネント

新規コンポーネント `CustomLinksEditor.tsx`:
```tsx
"use client";
import { useState } from "react";

export type CustomLink = { label: string; url: string };

export function CustomLinksEditor({ defaultLinks }: { defaultLinks?: CustomLink[] }) {
  const [links, setLinks] = useState<CustomLink[]>(defaultLinks ?? []);

  const handleAddLink = () => {
    setLinks([...links, { label: "", url: "" }]);
  };

  const handleRemoveLink = (index: number) => {
    setLinks(links.filter((_, i) => i !== index));
  };

  const handleLinkChange = (index: number, field: "label" | "url", value: string) => {
    const newLinks = [...links];
    newLinks[index] = { ...newLinks[index], [field]: value };
    setLinks(newLinks);
  };

  return (
    <fieldset>
      <legend className="text-sm font-medium text-neutral-600">カスタムリンク</legend>
      
      <div className="space-y-3">
        {links.map((link, index) => (
          <div key={index} className="flex gap-2">
            <input
              type="text"
              placeholder="ラベル(例: 自分のブログ)"
              value={link.label}
              onChange={(e) => handleLinkChange(index, "label", e.target.value)}
              className="flex-1 rounded border border-neutral-300 px-3 py-2 text-sm"
            />
            <input
              type="url"
              placeholder="https://example.com"
              value={link.url}
              onChange={(e) => handleLinkChange(index, "url", e.target.value)}
              className="flex-1 rounded border border-neutral-300 px-3 py-2 text-sm"
            />
            <button
              type="button"
              onClick={() => handleRemoveLink(index)}
              className="rounded bg-red-100 px-3 py-2 text-sm text-red-700 hover:bg-red-200"
            >
              削除
            </button>
          </div>
        ))}
      </div>
      
      <button
        type="button"
        onClick={handleAddLink}
        className="mt-2 rounded bg-neutral-200 px-4 py-2 text-sm text-neutral-700 hover:bg-neutral-300"
      >
        + 追加
      </button>

      {/* 送信用: hidden input に JSON 文字列として詰める */}
      <input type="hidden" name="customLinks" value={JSON.stringify(links)} />
    </fieldset>
  );
}
```

#### フォーム送信処理

`actions.ts: updateUserProfileAction`:
```typescript
export async function updateUserProfileAction(
  userId: Long,
  prevState: UpdateProfileState,
  formData: FormData
): Promise<UpdateProfileState> {
  // customLinks の JSON パース
  const customLinksJson = formData.get("customLinks") as string;
  let customLinks: CustomLink[] = [];
  try {
    customLinks = JSON.parse(customLinksJson || "[]");
  } catch {
    return { error: "カスタムリンクのパースに失敗しました" };
  }

  // socialLinks の構築(SocialLinkField の name="socialLinks.${name}" から)
  const socialLinks = {
    facebook: formData.get("socialLinks.facebook") || null,
    youtube: formData.get("socialLinks.youtube") || null,
    // ... 全14種
  };

  const payload: UserProfileInput = {
    firstName: formData.get("firstName") as string,
    lastName: formData.get("lastName") as string,
    displayName: formData.get("displayName") as string,
    nickname: formData.get("nickname") as string,
    websiteUrl: formData.get("websiteUrl") as string,
    bio: formData.get("bio") as string,
    locale: formData.get("locale") as string,
    avatarUrl: formData.get("avatarUrl") as string,
    department: formData.get("department") as string,
    position: formData.get("position") as string,
    socialLinks,
    customLinks
  };

  return await updateUserProfile(userId, payload);
}
```

### First Name / Last Name / Nickname / Website / 言語

**確認済み:**これらはすべて Phase 8-1 で既に実装済み(`User.java` フィールド存在、`UserProfileForm.tsx` 入力欄存在)。
**対応: 変更なし。既存の label/input をそのまま使う。** 計画文書で「既存対応済み、重複実装なし」と明記する。

## スコープ・実装項目

実装対象:

- [x] `V15__add_user_social_and_custom_links.sql` 作成
- [x] `SocialLinks.java` 新規 record 作成
- [x] `CustomLink.java` 新規 record 作成
- [x] `User.java`: socialLinks / customLinks フィールド追加
- [x] `UserProfileResponse.java` / `UserProfileUpdateRequest.java`: socialLinks / customLinks フィールド追加、email 追加
- [x] `UserService.updateUserProfile()`: socialLinks / customLinks の処理(上書き)
- [x] `UserProfileForm.tsx`: Email 表示欄、DisplayName プルダウン化、SNS セクション、カスタムリンク セクション実装
- [x] `DisplayNameSelect.tsx` 新規コンポーネント
- [x] `CustomLinksEditor.tsx` 新規コンポーネント
- [x] `actions.ts: updateUserProfileAction`: customLinks JSON パース、socialLinks 構築
- [x] `apiClient.ts`: `UserProfile` / `UserProfileInput` 型に socialLinks / customLinks / email 追加

対応外・スコープ外:

- SNS リンクの WordPress カスタムメタ同期(Phase 8 未決事項、保留継続)
- SNS アイコン表示(lucide-react に 14 種ブランドアイコンが揃わない見込み、テキストラベルのみ)
- カスタムリンクのドラッグ&ドロップ並べ替え(要件に含まれず、配列順 = 表示順)
- カスタムリンク の バリデーション強化(基本的な type="url" チェックのみ)
- Email 変更機能(既存の別エンドポイント(スコープ外)のまま)

## 実装順序

1. DB migration `V15` 作成・実行確認
2. record 型(`SocialLinks`, `CustomLink`)作成
3. `User.java` エンティティ拡張
4. DTO 拡張
5. `UserService.updateUserProfile()` 修正
6. フロント: `DisplayNameSelect.tsx` 実装
7. フロント: `CustomLinksEditor.tsx` 実装
8. フロント: `UserProfileForm.tsx` 修正(Email 表示、DisplayName プルダウン差し替え、SNS/カスタムリンク セクション追加)
9. フロント: `actions.ts` 修正(customLinks パース、socialLinks 構築)
10. `apiClient.ts` 型追加
11. テスト整備・実機検証

## テスト整備

- `UserServiceTest`:
  - `updateUserProfile()` に socialLinks 値をリクエストに含めた場合、永続化されることを確認
  - `updateUserProfile()` に customLinks 配列をリクエストに含めた場合、順序を保持したまま永続化されることを確認
  - socialLinks / customLinks が null / 空配列の場合の挙動
- `UserControllerTest`:
  - PUT `/api/users/{id}` に socialLinks / customLinks を含める成功ケース
  - 各フィールドが null 許可か等のバリデーション確認
- マイグレーション: Flyway が V15 を正常に適用・既存データが NULL のまま読めることを確認

## 実機検証

### SNS リンク

1. ユーザープロフィール編集画面を開く
2. 「SNS リンク」セクション内、14 個の URL 入力欄が表示されることを確認
3. 一部の SNS(Facebook, GitHub 等)に URL を入力して保存
4. 再度プロフィール編集画面を開くと、入力した URL が復帰されることを確認
5. 一度入力した URL を空欄にして保存、再読込で empty になることを確認

### カスタムリンク

1. 「カスタムリンク」セクション内、「+ 追加」ボタンが表示されることを確認
2. 「+ 追加」を 3 回クリック、3 行の「ラベル + URL」入力欄が出現することを確認
3. 1 行目: ラベル「自分のブログ」, URL「https://myblog.example.com」
4. 2 行目: ラベル「ポートフォリオ」, URL「https://portfolio.example.com」
5. 3 行目は入力せず「削除」ボタンで削除
6. 保存して再読込、2 行のカスタムリンク + その順序が保持されていることを確認
7. さらに行を追加・削除してもデータが正しく保存されることを確認

### DisplayName プルダウン化

1. 「表示名」フィールドが `<input>` ではなく `<select>` になっていることを確認
2. 姓「山田」, 名「太郎」を入力すると、プルダウンに以下の候補が出現:
   - 「太郎」(名)
   - 「山田」(姓)
   - 「山田 太郎」
   - 「太郎 山田」
   - (emailローカルパート、入力している場合)
3. 候補から「山田 太郎」を選択して保存
4. 再読込で displayName が「山田 太郎」のまま復帰されることを確認
5. 既に保存済みの displayName が候補に無い場合(手動編集値)、先頭に表示されることを確認

### Email 表示のみ

1. プロフィール編集フォーム冒頭に「メールアドレス」欄が表示されることを確認
2. 当該欄が disabled(灰色背景、クリック不可)であることを確認
3. 補助テキスト「（変更は設定から行えます）」が表示されることを確認

