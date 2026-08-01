# 02. プロジェクトのマスター環境設定(テスト/本番のいずれか)

## 目的

プロジェクトごとに、テスト環境・本番環境のどちらを「マスター環境」にするか設定できるようにする。マスター環境は、[03-bulk-management-category-tag.md](03-bulk-management-category-tag.md)・[04-bulk-management-plugin-theme.md](04-bulk-management-plugin-theme.md)で実装する比較テーブルにおいて「正」とみなす環境であり、差分のハイライト(赤字表示)・同期(マスター→非マスター)の基準として使われる。ローカル環境はマスターに指定できない(開発者の手元環境を正とはみなさない)。

## 現状確認

- [Project.java](../../api/src/main/java/com/letsblog/api/domain/Project.java)は`localSiteId`/`testSiteId`/`productionSiteId`の3スロットのみを持ち、どの環境を「正」とみなすかという概念が存在しない
- 03/04の比較テーブルは、マスター環境がどれかを前提に「マスターとの差分」「マスターへの同期」を実現するため、本タスクを先に完了させる必要がある

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 選択肢 | `test`/`production`のみ(`local`は選択不可。APIレベルでもバリデーションする) |
| 初期値 | 新規プロジェクト作成時・既存プロジェクトとも既定値は`test`とする(DBの`DEFAULT 'test'`で対応し、明示的な初期設定UIは設けない。プロジェクト作成直後にテスト環境がまだ紐付いていなくても、後から比較テーブルを使う時点でテスト環境が紐付いていれば動作する) |
| マスター環境のスロットにサイトが紐付いていない/managedでない場合 | この設定自体は保存できる(単なる希望値のため)。03/04の比較テーブル側で「マスター環境(テスト)にサイトが紐付いていません」等のエラー表示を行い、比較・同期機能を無効化する |
| UIの配置 | プロジェクト詳細画面に新規コンポーネント`MasterEnvironmentSelector.tsx`を配置する(一括管理パネルの直前、環境スロット一覧の直後) |
| 権限 | 既存の環境同期・一括管理と同じく管理者(`adminAuthorizationService.requireAdmin()`)のみ変更可能 |
| 確認UX | `window.confirm`は不要(破壊的操作ではないため、既存の`ProjectNameForm.tsx`と同じ即時保存パターンとする) |

## アーキテクチャ・実装詳細

### データベース(新規マイグレーション `V18__add_project_master_environment.sql`)

```sql
ALTER TABLE projects
    ADD COLUMN master_environment VARCHAR(20) NOT NULL DEFAULT 'test';
```

(`test`/`production`のみを許容する制約はアプリケーション層で検証する。既存の`local_site_id`等と同様、DB側にCHECK制約は設けない方針を踏襲)

### バックエンド(Spring Boot)

`Project.java`に追加:

```java
@Column(name = "master_environment", nullable = false, length = 20)
private String masterEnvironment = "test";
```

`ProjectResponse.java`に`masterEnvironment`フィールドを追加し、`from()`で`project.getMasterEnvironment()`をマッピングする。

`dto/UpdateMasterEnvironmentRequest.java`(新規): `record UpdateMasterEnvironmentRequest(@NotBlank String masterEnvironment) {}`

`ProjectService.java`に追加:

```java
@Transactional
public ProjectResponse updateMasterEnvironment(Long projectId, String masterEnvironment) {
    if (!Set.of("test", "production").contains(masterEnvironment)) {
        throw new IllegalArgumentException("マスター環境はtest/productionのいずれかを指定してください");
    }
    Project project = getProjectEntity(projectId); // 既存のfindByIdヘルパーを再利用
    project.setMasterEnvironment(masterEnvironment);
    return toResponse(projectRepository.save(project));
}
```

`ProjectController.java`に追加:

```java
@PutMapping("/{id}/master-environment")
public ProjectResponse updateMasterEnvironment(
        @PathVariable Long id, @Valid @RequestBody UpdateMasterEnvironmentRequest request) {
    adminAuthorizationService.requireAdmin();
    return projectService.updateMasterEnvironment(id, request.masterEnvironment());
}
```

### フロントエンド

`web/src/lib/apiClient.ts`:

- `Project`型に`masterEnvironment: "test" | "production"`を追加
- `updateMasterEnvironment(projectId: number, masterEnvironment: "test" | "production", actor)`を追加(`PUT /api/projects/{id}/master-environment`)

`web/src/app/projects/[id]/actions.ts`:

```ts
export interface UpdateMasterEnvironmentState {
  error?: string;
  success?: boolean;
}

export async function updateMasterEnvironmentAction(
  projectId: number,
  _prevState: UpdateMasterEnvironmentState,
  formData: FormData
): Promise<UpdateMasterEnvironmentState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const masterEnvironment = String(formData.get("masterEnvironment") ?? "");
  if (masterEnvironment !== "test" && masterEnvironment !== "production") {
    return { error: "テスト環境または本番環境を選択してください。" };
  }
  try {
    await updateMasterEnvironment(projectId, masterEnvironment, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}
```

`web/src/app/projects/[id]/MasterEnvironmentSelector.tsx`(新規):

- `<select>`でテスト/本番を選択(値変更時に即座に`formAction`をsubmitする、または「保存」ボタンを添える。`ProjectNameForm.tsx`と同じ即時保存パターンに合わせ「保存」ボタン方式とする)
- 選択中の環境にサイトが紐付いていない場合は`<option>`ラベルに「(未紐付け)」を付与して視認性を上げる(選択自体は許可する)
- 保存成功時に一括管理パネル(03/04)が参照する`project.masterEnvironment`が更新されるよう、`page.tsx`で`revalidatePath`済みの`project`を再取得して渡す

`web/src/app/projects/[id]/page.tsx`に`<MasterEnvironmentSelector projectId={project.id} project={project} />`を追加。

## スコープ・実装項目

実装対象:

- [ ] `api/src/main/resources/db/migration/V18__add_project_master_environment.sql`(新規)
- [ ] `api/src/main/java/com/letsblog/api/domain/Project.java`: `masterEnvironment`フィールド追加
- [ ] `api/src/main/java/com/letsblog/api/dto/ProjectResponse.java`: `masterEnvironment`追加
- [ ] `api/src/main/java/com/letsblog/api/dto/UpdateMasterEnvironmentRequest.java`(新規)
- [ ] `api/src/main/java/com/letsblog/api/service/ProjectService.java`: `updateMasterEnvironment()`
- [ ] `ProjectController.java`: `PUT /{id}/master-environment`
- [ ] `web/src/lib/apiClient.ts`: `Project.masterEnvironment`型・`updateMasterEnvironment()`
- [ ] `web/src/app/projects/[id]/MasterEnvironmentSelector.tsx`(新規)
- [ ] `web/src/app/projects/[id]/page.tsx`: コンポーネント追加
- [ ] `web/src/app/projects/[id]/actions.ts`: `updateMasterEnvironmentAction`

対象外・スコープ外:

- ローカルをマスターに指定できるようにするオプション
- プロジェクト作成時にマスター環境を初期設定するウィザードUI(既定値`test`で作成し、後から変更する運用とする)

## 実装順序

1. `V18__add_project_master_environment.sql` → `Project`ドメイン
2. `ProjectResponse`/`UpdateMasterEnvironmentRequest`/`ProjectService`/`ProjectController`
3. フロント: `apiClient.ts` → `MasterEnvironmentSelector.tsx` → `page.tsx` → `actions.ts`
4. テスト整備・実機検証

## テスト整備

- `ProjectServiceTest`(既存クラスへ追加、なければ新規): `updateMasterEnvironment()`が`test`/`production`を受け付けること、`local`や不正な値を指定した場合に`IllegalArgumentException`がスローされること、既存プロジェクトの既定値が`test`であること(マイグレーション適用直後の想定)
- `ProjectControllerTest`(既存クラスへ追加): `PUT /master-environment`のadmin権限チェック、バリデーションエラー時の400応答

## 実機検証

1. プロジェクト詳細画面でマスター環境を「本番」に変更し、保存後に選択状態が保持される(ページ再読み込み後も反映されている)ことを確認
2. `local`を指定するAPIリクエストが400エラーになることを確認
3. マスター環境に指定した環境スロットにサイトが紐付いていない状態でも設定の保存自体は成功することを確認
