# 03. プロジェクト⇔サイト⇔ユーザーの可視化

## 目的

Phase 8 で実装されたプロジェクト管理機能(複数WordPress環境の一元管理、プロジェクト参加ユーザーの自動登録)の透明性を高め、以下の関連情報を管理画面で視覚的に把握できるようにする:

1. **サイト一覧にプロジェクト表示**: 各サイトが属するプロジェクトを列に表示
2. **ユーザー一覧に参加プロジェクト表示**: 各ユーザーが参加しているプロジェクト一覧を列に表示

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| サイト⇔プロジェクト表示方式 | バックエンド変更なし、フロント側で `GET /api/projects` 結果と join(YAGNI) |
| ユーザー⇔プロジェクト表示方式 | 新規バルクエンドポイント `GET /api/project-users` を追加(N+1 回避) |
| サイト一覧列追加位置 | 「サイトキー」「表示名」「CMS種別」「URL」「登録日」の既存列の中、URL の手前に「プロジェクト」列を挿入 |
| ユーザー一覧列追加位置 | 「メールアドレス」「権限」「登録日」の既存列の手前に「参加プロジェクト」列を挿入 |
| プロジェクトなし時の表示 | `「-」`または空白(要実装時に決定) |
| 管理者権限 | `GET /api/project-users` は管理者専用 |

## アーキテクチャ・実装詳細

### C-1: サイト一覧にプロジェクト表示

#### データモデルの理解

`Project.java`(確認済み):
- `localSiteId`, `testSiteId`, `productionSiteId`(각各 nullable Long FK to sites.id)
- つまり、1 プロジェクトは最大 3 つのサイト(ローカル・テスト・本番)を持つ

`Site.java`:
- `Project` への逆参照なし(unidirectional)

よって、「サイトから見て、このサイトはどのプロジェクトに属するか」を知るには、全 projects を取得して各 FK を check する必要がある。

#### 実装方法: フロント側 join

`web/src/app/sites/page.tsx`(確認済み, Server Component):

```tsx
// Before
export default async function SitesPage() {
  const [sites, session] = await Promise.all([
    listSites().catch(() => []), 
    getSession()
  ]);
  ...
}

// After
export default async function SitesPage() {
  const [sites, projects, session] = await Promise.all([
    listSites().catch(() => []), 
    listProjects().catch(() => []),
    getSession()
  ]);
  
  // siteId → project name のマップ構築
  const siteToProject = new Map<number, { name: string; environment: string }>();
  for (const project of projects) {
    if (project.localSiteId && sites.some(s => s.id === project.localSiteId)) {
      siteToProject.set(project.localSiteId, { name: project.name, environment: 'ローカル' });
    }
    if (project.testSiteId && sites.some(s => s.id === project.testSiteId)) {
      siteToProject.set(project.testSiteId, { name: project.name, environment: 'テスト' });
    }
    if (project.productionSiteId && sites.some(s => s.id === project.productionSiteId)) {
      siteToProject.set(project.productionSiteId, { name: project.name, environment: '本番' });
    }
  }
  
  return (
    <table>
      <thead>
        <tr>
          <th>サイトキー</th>
          <th>表示名</th>
          <th>CMS種別</th>
          <th>プロジェクト</th>  {/* 新規列 */}
          <th>URL</th>
          <th>登録日</th>
          ...
        </tr>
      </thead>
      <tbody>
        {sites.map(site => {
          const projectInfo = siteToProject.get(site.id);
          return (
            <tr key={site.id}>
              ...
              <td>{projectInfo ? `${projectInfo.name}(${projectInfo.environment})` : '-'}</td>
              ...
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
```

#### 理由(バックエンド変更なしの判断根拠)

- `GET /api/projects` は既に Phase 8 で実装済み。Spring Boot API がアクセス可能な全 projects を返す
- サイト数・プロジェクト数はまだ小規模(数〜数十件)。フロント側で `sites.length × projects.length` の O(n²) join でも negligible
- Server Component はリクエスト時点で両方のデータを並行 fetch できるため、N+1 にならない(合計 2 回の API 呼び出しのみ)
- 将来的に規模が大きくなった場合は、バックエンドに `GET /api/sites` のレスポンスに `projectId`/`projectName`/`environment` フィールドを追加する最適化がある。今回はそこまで必要と判断できず(YAGNI)

### C-2: ユーザー一覧に参加プロジェクト表示

#### 現状の gap

既存 API:
- `GET /api/projects/{id}/users`: 1 プロジェクトのメンバー一覧を返す(forward direction)
- **逆方向(ユーザーから見てどのプロジェクトに属するか)がない**

ユーザー一覧ページ(`web/src/app/users/page.tsx`)で、各ユーザー行に参加プロジェクトを表示するには、現状では以下のアプローチしかない:
1. **ユーザーごとに全プロジェクト分ループ**して `GET /api/projects/{id}/users` を呼ぶ → **N+1**(ユーザー数 × プロジェクト数 = 数十〜数百リクエスト)
2. **バルクエンドポイント**を新設して全ペアを 1 回の fetch で取得 → **推奨**

#### 実装方法: 新規バルクエンドポイント

**バックエンド:**

新規DTO `ProjectUserSummaryResponse.java`:
```java
public record ProjectUserSummaryResponse(
    Long projectId,
    Long userId,
    String wpRole
) { }
```

`ProjectController.java` に新規 endpoint 追加:
```java
@GetMapping("/project-users")  // または GET /api/project-users(新規ルート)
public List<ProjectUserSummaryResponse> listAllProjectUsers() {
    adminAuthorizationService.requireAdmin();
    return projectUserSyncService.listAllProjectUsers();  // 新規サービスメソッド
}
```

`ProjectService` または新規 `ProjectUserService` に新規メソッド追加:
```java
public List<ProjectUserSummaryResponse> listAllProjectUsers() {
    return projectUserRepository.findAll()  // JpaRepository の標準メソッド、Project/User あり
        .stream()
        .map(pu -> new ProjectUserSummaryResponse(pu.getProjectId(), pu.getUserId(), pu.getWpRole()))
        .toList();
}
```

(`ProjectUserRepository` は既存の `findAll()` をそのまま利用可能。新規メソッド実装不要の見込み)

**フロント:**

`web/src/lib/apiClient.ts`:
```typescript
export async function listProjectUsers(): Promise<ProjectUserSummary[]> {
  const response = await fetch(`${API_BASE}/project-users`, {
    headers: { 'X-API-Key': apiKey },
  });
  if (!response.ok) throw new Error('Failed to fetch project users');
  return response.json();
}

export type ProjectUserSummary = {
  projectId: number;
  userId: number;
  wpRole: string;
};
```

`web/src/app/users/page.tsx`:
```tsx
export default async function UsersPage() {
  const session = await requireAdminSession();
  const [users, projects, projectUsers] = await Promise.all([
    listUsers().catch(() => []),
    listProjects().catch(() => []),
    listProjectUsers().catch(() => [])
  ]);

  // userId → [project name, ...] のマップ構築
  const userToProjects = new Map<number, string[]>();
  for (const pu of projectUsers) {
    const project = projects.find(p => p.id === pu.projectId);
    if (project) {
      if (!userToProjects.has(pu.userId)) {
        userToProjects.set(pu.userId, []);
      }
      userToProjects.get(pu.userId)!.push(project.name);
    }
  }

  return (
    <table>
      <thead>
        <tr>
          <th>参加プロジェクト</th>  {/* 新規列、ここに挿入 */}
          <th>メールアドレス</th>
          <th>権限</th>
          <th>登録日</th>
          ...
        </tr>
      </thead>
      <tbody>
        {users.map(user => {
          const projects = userToProjects.get(user.id) || [];
          return (
            <tr key={user.id}>
              <td>{projects.length > 0 ? projects.join(', ') : '-'}</td>
              ...
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
```

#### N+1 回避の確認

- フロント側: `Promise.all([listUsers(), listProjects(), listProjectUsers()])` で 3 API 呼び出しのみ(O(1))
- バックエンド側: `ProjectUserRepository.findAll()` で全 rows 1 回の query

→ N+1 なし、efficient

#### 「単数版は不要」の理由

`GET /api/users/{id}/projects` の単数版エンドポイントは、ユーザー詳細ページで使う可能性があるが:
- 今回のフィードバックは「ユーザー**一覧**ページでプロジェクト表示」のみ
- 一覧ページでは各行に単数版を呼ぶと N+1 になる(悪い設計)
- 詳細ページは実装予定がない(フィードバックに記載なし)

→ スコープ外、将来必要になれば別途追加(YAGNI 方針に合致)

## スコープ・実装項目

実装対象:

- [x] `ProjectUserSummaryResponse.java` (新規)
- [x] `ProjectController.java`: `GET /api/project-users` endpoint 追加
- [x] サービス層: `listAllProjectUsers()` メソッド追加(既存 `ProjectService` または新規リポジトリメソッド呼び出し)
- [x] `web/src/lib/apiClient.ts`: `listProjectUsers()` 関数・型追加
- [x] `web/src/app/sites/page.tsx`: `listProjects()` fetch 追加、テーブルに「プロジェクト」列追加
- [x] `web/src/app/users/page.tsx`: `listProjects()`, `listProjectUsers()` fetch 追加、テーブルに「参加プロジェクト」列追加

対象外・スコープ外:

- 単数版 `GET /api/users/{id}/projects` エンドポイント
- プロジェクト名クリック時のプロジェクト詳細へのリンク(将来の convenience, 今回は表示のみ)
- モバイル表示時のテーブル column 削減・rwd 最適化(既存のテーブル layout と同様)
- ページング・フィルタリング(既存の一覧ページと同じく pagination なし)

## 実装順序

1. バックエンド: `ProjectUserSummaryResponse` DTO 作成
2. バックエンド: `ProjectController.listAllProjectUsers()` endpoint 実装
3. フロント: `apiClient.listProjectUsers()` 関数・型追加
4. フロント: `sites/page.tsx` 修正(listProjects fetch + join + column 追加)
5. フロント: `users/page.tsx` 修正(listProjects + listProjectUsers fetch + join + column 追加)
6. テスト整備・実機検証

## テスト整備

- `ProjectControllerTest`:
  - `GET /api/project-users` が全 project_id/user_id/wpRole ペアを返すことを検証
  - 非管理者アクセス時 403 が返されることを確認
- E2E/フロント実機検証: 以下参照

## 実機検証

### C-1 サイト一覧にプロジェクト表示

1. 管理画面の「サイト」ページを開く
2. テーブルに新規列「プロジェクト」が表示されることを確認
3. プロジェクトに紐付いたサイト(local/test/production slot に指定されているサイト)の行に、プロジェクト名と環境(ローカル/テスト/本番)が表示されることを確認:
   - 例: 「MyProject(ローカル)」
4. プロジェクトに紐付いていないサイト(外部登録サイト等)の行に「-」が表示されることを確認

### C-2 ユーザー一覧に参加プロジェクト表示

1. 管理画面の「ユーザー管理」ページを開く
2. テーブルに新規列「参加プロジェクト」が表示されることを確認
3. プロジェクトに参加しているユーザーの行に、参加プロジェクト名がカンマ区切りで表示されることを確認:
   - 例: 「ProjectA, ProjectB」
4. プロジェクトに未参加のユーザーの行に「-」が表示されることを確認
5. 複数プロジェクトに参加しているユーザーについて、全プロジェクト名がリストアップされることを確認

