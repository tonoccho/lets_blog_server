# 03. 計画の受け入れ(GitHub issue 作成)

## 目的

ユーザーが計画画面でチェックボックス選択したタイトル一覧を受け取り、GitHub API 経由で 1 タイトル 1 issue として自動登録する仕組みを構築する。issue 作成に失敗した場合も他のタイトルへの影響を避け、個別に結果を集約して返す。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| GitHub API ライブラリ | Octokit 等は導入しない。`OllamaClient`と同じく`RestClient`の薄いラッパー(`GithubClient`)を自前実装 |
| API エンドポイント | `https://api.github.com/repos/{owner}/{repo}/issues`(REST API v3) |
| 認証方式 | Personal Access Token (Bearer トークン)。`Authorization: Bearer <token>`ヘッダ |
| issue タイトル | ユーザーが選択したテキストをそのまま使用 |
| issue 本文(body) | 空文字列。タイトルのみのシンプルな issue として登録(将来のカスタマイズ余地は残す) |
| issue ラベル・アサイン | 付与しない |
| 設定確認 | プロジェクトの`githubRepository`未設定 or ユーザーの`githubToken`未設定の場合、呼び出し前に意味のあるエラーメッセージで`IllegalStateException`をスロー |
| 部分失敗時の動作 | 1 issue 作成に失敗しても他を続ける。1 件ずつ try/catch で捕捉し、成功/失敗を`AcceptPlanResultItem`に集約 |
| レスポンス形式 | `List<AcceptPlanResultItem>`(1 タイトル 1 アイテム、成功時は issue number と HTML URL、失敗時はエラーメッセージを含む) |
| アクセス制御 | 既存の`adminAuthorizationService.requireAdmin()`で管理者のみ |

## アーキテクチャ・実装詳細

### 1. バックエンド

#### 新規例外クラス

**`GithubApiException.java` (新規)**:

```java
package com.letsblog.api.exception;

public class GithubApiException extends RuntimeException {
    public GithubApiException(String message) {
        super(message);
    }

    public GithubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

#### GitHub API クライアント

**`GithubIssue.java` (新規)**:

```java
package com.letsblog.api.github;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GithubIssue {
    @JsonProperty("number")
    private int number;

    @JsonProperty("html_url")
    private String htmlUrl;

    // getter / setter...
    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }
}
```

**`GithubClient.java` (新規)**:

```java
package com.letsblog.api.github;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.api.exception.GithubApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GithubClient {

    private final RestClient client;

    public GithubClient() {
        this.client = RestClient.builder()
            .baseUrl("https://api.github.com")
            .build();
    }

    /**
     * GitHub issue を作成する
     *
     * @param token         Personal Access Token (Bearer 形式で送信)
     * @param owner         リポジトリ owner
     * @param repo          リポジトリ名
     * @param title         issue タイトル
     * @param body          issue 本文(空文字列可)
     * @return GithubIssue 作成された issue の情報
     * @throws GithubApiException API エラーの場合
     */
    public GithubIssue createIssue(String token, String owner, String repo, String title, String body) {
        try {
            ObjectNode bodyNode = JsonNodeFactory.instance.objectNode()
                .put("title", title)
                .put("body", body != null ? body : "");

            GithubIssue result = client.post()
                .uri("/repos/{owner}/{repo}/issues", owner, repo)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(bodyNode)
                .retrieve()
                .body(GithubIssue.class);

            return result;
        } catch (RestClientResponseException e) {
            String message = "GitHub issue 作成に失敗しました";
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                message = "GitHub の認証に失敗しました。Personal Access Token が無効またはスコープが不足している可能性があります。";
            } else if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                message = "リポジトリが見つかりません: " + owner + "/" + repo;
            } else if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                message = "issue の作成に失敗しました: " + e.getResponseBodyAsString();
            }
            throw new GithubApiException(message, e);
        } catch (Exception e) {
            throw new GithubApiException("GitHub API 呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }
}
```

#### DTO

**`AcceptPlanResultItem.java` (新規)**:

```java
package com.letsblog.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AcceptPlanResultItem(
    String title,
    Integer issueNumber,
    String issueUrl,
    String error
) {
    // 成功時: issueNumber, issueUrl を含む
    // 失敗時: error を含む
}
```

**`AcceptPlanRequest.java` (新規)**:

```java
package com.letsblog.api.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record AcceptPlanRequest(
    @NotEmpty(message = "選択するタイトルが 1 件以上必要です")
    List<String> titles
) {
}
```

**`AcceptPlanResponse.java` (新規)**:

```java
package com.letsblog.api.dto;

import java.util.List;

public record AcceptPlanResponse(
    List<AcceptPlanResultItem> results
) {
}
```

#### Service

**`ArticlePlanService.java` に追加**:

```java
@Autowired
private GithubClient githubClient;

@Autowired
private UserService userService;

@Autowired
private ProjectService projectService;

/**
 * 記事計画を受け入れ、GitHub issue として登録する
 */
@Transactional(readOnly = true)
public AcceptPlanResponse acceptPlan(Long projectId, Long userId, List<String> titles) {
    // プロジェクト・ユーザーの設定を確認
    Project project = projectService.getProjectEntity(projectId);
    if (!project.isGithubRepositoryConfigured()) {
        throw new IllegalStateException(
            "このプロジェクトに GitHub リポジトリが紐付けられていません。"
            + "プロジェクト詳細ページから設定してください。");
    }

    String token = userService.getDecryptedGithubToken(userId);  // 未設定時は IllegalStateException をスロー
    String[] repoParts = project.getGithubRepository().split("/");
    String owner = repoParts[0];
    String repo = repoParts[1];

    // 1 タイトルずつ issue 作成を試み、結果を集約
    List<AcceptPlanResultItem> results = new ArrayList<>();
    for (String title : titles) {
        try {
            GithubIssue issue = githubClient.createIssue(token, owner, repo, title, "");
            results.add(new AcceptPlanResultItem(
                title,
                issue.getNumber(),
                issue.getHtmlUrl(),
                null
            ));
        } catch (Exception e) {
            results.add(new AcceptPlanResultItem(
                title,
                null,
                null,
                e.getMessage()
            ));
        }
    }

    return new AcceptPlanResponse(results);
}
```

#### Controller

**`ArticlePlanController.java` に追加**:

```java
@PostMapping("/accept")
public AcceptPlanResponse acceptPlan(
        @PathVariable Long projectId,
        @Valid @RequestBody AcceptPlanRequest request) {
    adminAuthorizationService.requireAdmin();
    Long userId = getCurrentUserId();
    return articlePlanService.acceptPlan(projectId, userId, request.titles());
}
```

### 2. フロントエンド

#### `web/src/app/projects/[id]/plan/ArticlePlanProposals.tsx` を拡張

```typescript
"use client";

import { useState } from "react";
import { suggestArticlePlanTitles, acceptArticlePlan } from "@/lib/apiClient";
import { PlanChatMessage, AcceptPlanResultItem } from "@/lib/apiClient";

export function ArticlePlanProposals({
  projectId,
  initialHistory = [],
}: {
  projectId: number;
  initialHistory?: PlanChatMessage[];
}) {
  const [titles, setTitles] = useState<string[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [isLoading, setIsLoading] = useState(false);
  const [results, setResults] = useState<AcceptPlanResultItem[] | null>(null);
  const [isAccepting, setIsAccepting] = useState(false);

  const handleSuggestTitles = async () => {
    if (initialHistory.length === 0) {
      alert("まずチャットで記事のテーマを掘り下げてください。");
      return;
    }

    setIsLoading(true);
    try {
      const response = await suggestArticlePlanTitles(projectId, {
        history: initialHistory,
      });
      setTitles(response.titles);
      setSelected(new Set());
      setResults(null);
    } catch (error) {
      alert("タイトル提案に失敗しました: " + (error instanceof Error ? error.message : String(error)));
    } finally {
      setIsLoading(false);
    }
  };

  const handleAcceptPlan = async () => {
    if (selected.size === 0) return;

    setIsAccepting(true);
    try {
      const response = await acceptArticlePlan(projectId, {
        titles: Array.from(selected),
      });
      setResults(response.results);
      setTitles([]);
      setSelected(new Set());
    } catch (error) {
      alert("計画の受け入れに失敗しました: " + (error instanceof Error ? error.message : String(error)));
    } finally {
      setIsAccepting(false);
    }
  };

  const toggleSelected = (title: string) => {
    const newSelected = new Set(selected);
    if (newSelected.has(title)) {
      newSelected.delete(title);
    } else {
      newSelected.add(title);
    }
    setSelected(newSelected);
  };

  return (
    <div className="rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="mb-4 font-medium">記事タイトル提案</h2>

      {/* タイトル提案フェーズ */}
      {results === null && (
        <>
          <button
            onClick={handleSuggestTitles}
            disabled={isLoading || initialHistory.length === 0}
            className="mb-4 rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {isLoading ? "提案取得中…" : "タイトル提案を取得"}
          </button>

          {titles.length === 0 ? (
            <p className="text-sm text-neutral-500">
              タイトル提案はまだありません。チャットで壁打ちしてから取得してください。
            </p>
          ) : (
            <>
              <div className="mb-4 space-y-2">
                {titles.map((title, idx) => (
                  <label
                    key={idx}
                    className="flex items-center gap-2 rounded border border-neutral-200 p-3 hover:bg-neutral-50"
                  >
                    <input
                      type="checkbox"
                      checked={selected.has(title)}
                      onChange={() => toggleSelected(title)}
                      className="cursor-pointer"
                    />
                    <span className="flex-1 text-sm">{title}</span>
                  </label>
                ))}
              </div>

              <button
                onClick={handleAcceptPlan}
                disabled={selected.size === 0 || isAccepting}
                className="w-full rounded bg-green-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
              >
                {isAccepting
                  ? "登録中…"
                  : `選択した記事 (${selected.size} 件) の計画を受け入れる`}
              </button>
            </>
          )}
        </>
      )}

      {/* 登録結果フェーズ */}
      {results !== null && (
        <>
          <div className="mb-4 space-y-2">
            {results.map((item, idx) => (
              <div
                key={idx}
                className={`rounded border p-3 text-sm ${
                  item.error
                    ? "border-red-200 bg-red-50"
                    : "border-green-200 bg-green-50"
                }`}
              >
                <div className="font-medium">{item.title}</div>
                {item.error ? (
                  <div className="text-red-700">✗ {item.error}</div>
                ) : (
                  <div className="text-green-700">
                    ✓ Issue{" "}
                    <a
                      href={item.issueUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="underline"
                    >
                      #{item.issueNumber}
                    </a>
                    {" "}として登録しました
                  </div>
                )}
              </div>
            ))}
          </div>

          <button
            onClick={() => {
              setResults(null);
              setTitles([]);
              setSelected(new Set());
            }}
            className="w-full rounded bg-neutral-600 px-4 py-2 text-sm text-white"
          >
            別のテーマで提案を取得
          </button>
        </>
      )}
    </div>
  );
}
```

#### `web/src/lib/apiClient.ts` に追加

**型追加**:

```typescript
export interface AcceptPlanResultItem {
  title: string;
  issueNumber?: number;
  issueUrl?: string;
  error?: string;
}

export interface AcceptPlanResponse {
  results: AcceptPlanResultItem[];
}
```

**関数追加**:

```typescript
export function acceptArticlePlan(
  projectId: number,
  data: { titles: string[] },
  actor?: ActorInfo
): Promise<AcceptPlanResponse> {
  return apiFetch<AcceptPlanResponse>(
    `/api/projects/${projectId}/article-plan/accept`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
      actor,
    }
  );
}
```

## スコープ・実装項目

実装対象:

- [ ] 新規例外クラス: `GithubApiException`
- [ ] GitHub API クライアント: `GithubClient`, `GithubIssue`
- [ ] DTO: `AcceptPlanRequest`, `AcceptPlanResponse`, `AcceptPlanResultItem` 新規作成
- [ ] Service: `ArticlePlanService.acceptPlan()` 実装
- [ ] Controller: `ArticlePlanController.acceptPlan()` エンドポイント追加
- [ ] フロント: `ArticlePlanProposals.tsx` を拡張(受け入れボタン・結果表示)、`actions.ts` に`acceptPlanAction` 追加(必要に応じて)
- [ ] API Client: `apiClient.ts` に型・関数追加
- [ ] テスト: Unit テスト整備

対象外・スコープ外:

- GitHub API の非同期ジョブ化・バッチ処理(1 件ずつ同期作成のまま)
- issue ラベル・マイルストーン・アサイン
- GitHub GraphQL API への切り替え
- Webhook 登録・自動更新通知

## 実装順序

1. DTO・例外クラス・GithubClient 実装
2. Service/Controller 実装・ユニットテスト
3. フロントエンド実装
4. 実機検証

## テスト整備

### Unit Tests

**`GithubClientTest`**:

- `MockRestServiceServer` を用いて GitHub API のリクエスト・レスポンスを検証
- 成功時: HTTP 201 で `GithubIssue` が正しくデシリアライズされることを確認
- 401 Unauthorized: `githubTokenConfigured` が無効な場合のエラーメッセージが含まれることを確認
- 404 Not Found: リポジトリが存在しない場合のエラーメッセージ
- その他 HTTP エラー: 汎用的なエラーメッセージが返ることを確認

**`ArticlePlanServiceTest` に追加**:

- `acceptPlan()` で未設定のリポジトリ/トークンが検出され、`IllegalStateException` をスロー
- 複数タイトルの登録で、1 件失敗してもリストの作成が続くこと(部分失敗の集約)
- 成功時アイテムに issue number と URL が含まれること
- 失敗時アイテムに error メッセージが含まれること

### 実機検証

- 実際に GitHub に接続し、テスト用リポジトリに issue を作成
- 3 タイトル選択した場合、3 件の issue が作成されることを確認
- 1 件の issue 作成に失敗(例: 不正なトークン)した場合も、他は成功し、結果に失敗の詳細が表示されること
- GitHub 上の issue URL をクリックして、正しく issue ページへ遷移することを確認
- 未設定状態(トークン無し/リポジトリ無し)でボタンをクリック→エラーメッセージが表示されることを確認

## 未決事項

- issue 本文に「このテーマについてのチャット要約」を含めるかどうか(現在は空に決定済み、将来拡張の余地あり)
- issue の作成完了後、自動的に別のテーマで新規提案を始めるか、または「別のテーマで提案を取得」ボタンで明示的に遷移するか(現在後者)
