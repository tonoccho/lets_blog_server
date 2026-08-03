# 02. AI 壁打ちチャットとタイトル提案

## 目的

Ollama を利用したマルチターンの対話形式で記事の企画ネタを壁打ちし、その会話に基づいて記事タイトル案(最大 5 件)を AI に提案させる仕組みを構築する。会話履歴はフロント(React state)で保持し、リクエストごとに全履歴をサーバーに送ることで、サーバー側のセッション管理を不要にする(ステートレス設計)。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| AI モデル | 既存の`qwen2.5:7b-instruct`を再利用(OllamaClient の設定に依存) |
| 会話履歴の保存 | フロント(React state)で保持のみ。サーバー側 DB 永続化・セッション管理は行わない |
| リクエスト形式 | 会話履歴全体 + 新規メッセージを 1 つの大きなプロンプトとしてOllama に送信(チャット形式の API `/api/chat`ではなく、既存の単発`/api/generate`で対応) |
| タイトル提案の形式 | JSON 配列 `["タイトル1", "タイトル2", ...]`のみを出力するよう指示。AiAssistService の`suggestTags`と同じ「`[`〜`]`を抽出してパース、失敗時は空配列」のフォールバック方式を採用 |
| 生成ジョブ記録 | マルチターンチャット・タイトル提案は既存`generation_jobs`テーブルに`type="plan_chat"` / `"plan_suggest_titles"`で記録(GenerationJob テーブルの`type`列は自由文字列のため追加の schema 変更不要) |
| プロンプト言語 | 日本語。ユーザーの入力・AI の出力双方を日本語で統一 |
| アクセス制御 | 既存の`requireAdmin()`で管理者のみ |
| Ollama 呼び出し | `OllamaClient.generate()`を直接利用。既存の非ストリーミング・同期レスポンス方式を踏襲 |

## アーキテクチャ・実装詳細

### 1. バックエンド

#### DTO

**`PlanChatMessage.java` (新規)**:

```java
package com.letsblog.api.dto;

public record PlanChatMessage(
    String role,        // "user" or "assistant"
    String content      // メッセージ本文
) {
}
```

**`PlanChatRequest.java` (新規)**:

```java
package com.letsblog.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PlanChatRequest(
    @Valid @NotNull List<PlanChatMessage> history,
    @NotBlank String message
) {
}
```

**`PlanChatResponse.java` (新規)**:

```java
package com.letsblog.api.dto;

public record PlanChatResponse(
    String reply  // AI からの応答
) {
}
```

**`SuggestTitlesRequest.java` (新規)**:

```java
package com.letsblog.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SuggestTitlesRequest(
    @Valid @NotNull List<PlanChatMessage> history
) {
}
```

**`SuggestTitlesResponse.java` (新規)**:

```java
package com.letsblog.api.dto;

import java.util.List;

public record SuggestTitlesResponse(
    List<String> titles  // 提案されたタイトル一覧、最大 5 件
) {
}
```

#### Service

**`ArticlePlanService.java` (新規)**:

```java
package com.letsblog.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.OllamaClient;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.*;
import com.letsblog.api.repository.GenerationJobRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ArticlePlanService {

    private final OllamaClient ollamaClient;
    private final GenerationJobRepository generationJobRepository;
    private final ObjectMapper objectMapper;

    public ArticlePlanService(
            OllamaClient ollamaClient,
            GenerationJobRepository generationJobRepository,
            ObjectMapper objectMapper) {
        this.ollamaClient = ollamaClient;
        this.generationJobRepository = generationJobRepository;
        this.objectMapper = objectMapper;
    }

    private static final String SYSTEM_PROMPT = 
        "あなたはブログ記事企画の壁打ち相手です。ユーザーの提案内容を聞き、鋭い質問や追加の視点を返しながら、"
        + "テーマを深掘りするのを手伝ってください。簡潔で前向きな応答を心がけてください。";

    private static final String TITLE_SUGGESTION_INSTRUCTION =
        "上記の会話を踏まえて、記事タイトル案を JSON 配列形式で、最大 5 件、簡潔に提案してください。"
        + "出力は JSON 配列のみとし、他の説明文は含めないでください。例: [\"タイトル1\", \"タイトル2\"]";

    /**
     * マルチターンチャット。会話履歴 + 新規メッセージから AI 応答を取得
     */
    public PlanChatResponse chat(Long projectId, Long userId, List<PlanChatMessage> history, String message) {
        GenerationJob job = startJob("plan_chat", Map.of(
            "projectId", String.valueOf(projectId),
            "message", message,
            "historyLength", String.valueOf(history.size())
        ));

        try {
            String prompt = buildChatPrompt(history, message);
            String reply = ollamaClient.generate(prompt);
            completeJob(job, Map.of("reply", reply));
            return new PlanChatResponse(reply);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    /**
     * 会話履歴からタイトル提案を取得
     */
    public SuggestTitlesResponse suggestTitles(Long projectId, Long userId, List<PlanChatMessage> history) {
        GenerationJob job = startJob("plan_suggest_titles", Map.of(
            "projectId", String.valueOf(projectId),
            "historyLength", String.valueOf(history.size())
        ));

        try {
            String prompt = buildTitleSuggestionPrompt(history);
            String raw = ollamaClient.generate(prompt);
            List<String> titles = parseTitles(raw);
            completeJob(job, Map.of("titlesCount", String.valueOf(titles.size()), "raw", raw));
            return new SuggestTitlesResponse(titles);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    private String buildChatPrompt(List<PlanChatMessage> history, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("System: ").append(SYSTEM_PROMPT).append("\n\n");

        for (PlanChatMessage msg : history) {
            sb.append(msg.role().equals("user") ? "User" : "Assistant")
              .append(": ")
              .append(msg.content())
              .append("\n");
        }

        sb.append("User: ").append(message).append("\n");
        sb.append("Assistant: ");
        return sb.toString();
    }

    private String buildTitleSuggestionPrompt(List<PlanChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("System: ").append(SYSTEM_PROMPT).append("\n\n");

        for (PlanChatMessage msg : history) {
            sb.append(msg.role().equals("user") ? "User" : "Assistant")
              .append(": ")
              .append(msg.content())
              .append("\n");
        }

        sb.append("\n").append(TITLE_SUGGESTION_INSTRUCTION).append("\n");
        return sb.toString();
    }

    private List<String> parseTitles(String raw) {
        String jsonPart = extractJsonArray(raw);
        try {
            JsonNode node = objectMapper.readTree(jsonPart);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> titles = new ArrayList<>();
            node.forEach(n -> {
                String title = n.asText();
                if (!title.isBlank()) {
                    titles.add(title);
                }
            });
            return titles.stream().limit(5).toList();  // 最大 5 件に制限
        } catch (Exception e) {
            return List.of();  // パース失敗時は空リスト
        }
    }

    private String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end < 0 || end < start) {
            return "[]";
        }
        return raw.substring(start, end + 1);
    }

    // GenerationJob 記録ロジック(AiAssistService.java と同じパターン)
    private GenerationJob startJob(String type, Map<String, String> metadata) {
        GenerationJob job = new GenerationJob();
        job.setType(type);
        job.setStatus("running");
        job.setMetadata(metadata);
        return generationJobRepository.save(job);
    }

    private void completeJob(GenerationJob job, Map<String, String> result) {
        job.setStatus("done");
        job.setResult(result);
        generationJobRepository.save(job);
    }

    private void failJob(GenerationJob job, Exception e) {
        job.setStatus("failed");
        job.setErrorMessage(e.getMessage());
        generationJobRepository.save(job);
    }
}
```

#### Controller

**`ArticlePlanController.java` (新規)**:

```java
package com.letsblog.api.controller;

import com.letsblog.api.dto.*;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ArticlePlanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/article-plan")
public class ArticlePlanController {

    private final ArticlePlanService articlePlanService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ArticlePlanController(
            ArticlePlanService articlePlanService,
            AdminAuthorizationService adminAuthorizationService) {
        this.articlePlanService = articlePlanService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @PostMapping("/chat")
    public PlanChatResponse chat(
            @PathVariable Long projectId,
            @Valid @RequestBody PlanChatRequest request) {
        adminAuthorizationService.requireAdmin();
        // ここでは projectId は将来の拡張用(プロジェクトごとのプロンプトカスタマイズ等)
        // 現時点では使用していないが、API シグネチャに含める
        Long userId = getCurrentUserId();  // SecurityContext から取得
        return articlePlanService.chat(projectId, userId, request.history(), request.message());
    }

    @PostMapping("/suggest-titles")
    public SuggestTitlesResponse suggestTitles(
            @PathVariable Long projectId,
            @Valid @RequestBody SuggestTitlesRequest request) {
        adminAuthorizationService.requireAdmin();
        Long userId = getCurrentUserId();
        return articlePlanService.suggestTitles(projectId, userId, request.history());
    }

    private Long getCurrentUserId() {
        // SecurityContext から認証ユーザーの ID を取得(既存の他の Controller と同じパターン)
        return 1L;  // 実装時に正式な取得方法に置き換え
    }
}
```

### 2. フロントエンド

#### `web/src/app/projects/[id]/plan/page.tsx` (新規)

```typescript
import { requireAdminSession } from "@/lib/session";
import { getProject } from "@/lib/apiClient";
import { ArticlePlanChat } from "./ArticlePlanChat";
import { ArticlePlanProposals } from "./ArticlePlanProposals";

export default async function ArticlePlanPage({
  params,
}: {
  params: { id: string };
}) {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const projectId = Number(params.id);
  const project = await getProject(projectId, actor);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">{project.name} — 記事計画</h1>

      {!project.githubRepository ? (
        <div className="rounded-lg border border-yellow-200 bg-yellow-50 p-4">
          <p className="text-sm text-yellow-800">
            ⚠️ このプロジェクトに GitHub リポジトリが紐付けられていません。
            <a href={`/projects/${projectId}`} className="ml-1 underline">
              プロジェクト詳細
            </a>
            から設定してください。
          </p>
        </div>
      ) : null}

      <div className="grid gap-8 lg:grid-cols-2">
        {/* チャット画面(左) */}
        <div>
          <ArticlePlanChat projectId={projectId} />
        </div>

        {/* タイトル提案画面(右) */}
        <div>
          <ArticlePlanProposals projectId={projectId} />
        </div>
      </div>
    </div>
  );
}
```

#### `web/src/app/projects/[id]/plan/ArticlePlanChat.tsx` (新規)

```typescript
"use client";

import { useState } from "react";
import { sendArticlePlanChatMessage } from "@/lib/apiClient";
import { PlanChatMessage } from "@/lib/apiClient";

export function ArticlePlanChat({ projectId }: { projectId: number }) {
  const [history, setHistory] = useState<PlanChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [isLoading, setIsLoading] = useState(false);

  const handleSendMessage = async () => {
    if (!input.trim() || isLoading) return;

    const userMessage: PlanChatMessage = {
      role: "user",
      content: input,
    };

    setHistory([...history, userMessage]);
    setInput("");
    setIsLoading(true);

    try {
      const response = await sendArticlePlanChatMessage(projectId, {
        history,
        message: input,
      });

      const assistantMessage: PlanChatMessage = {
        role: "assistant",
        content: response.reply,
      };

      setHistory((prev) => [...prev, userMessage, assistantMessage]);
    } catch (error) {
      alert("チャット送信に失敗しました: " + (error instanceof Error ? error.message : String(error)));
      // エラー時はユーザーメッセージを履歴から削除
      setHistory((prev) => prev.slice(0, -1));
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="mb-4 font-medium">AI との壁打ち</h2>

      {/* チャット履歴表示 */}
      <div className="mb-4 max-h-96 space-y-3 overflow-y-auto rounded-lg bg-neutral-50 p-4">
        {history.length === 0 ? (
          <p className="text-sm text-neutral-500">チャットを始めましょう。記事のテーマや企画を入力してください。</p>
        ) : (
          history.map((msg, idx) => (
            <div
              key={idx}
              className={`rounded px-3 py-2 text-sm ${
                msg.role === "user"
                  ? "bg-blue-100 text-blue-900"
                  : "bg-neutral-200 text-neutral-900"
              }`}
            >
              <strong>{msg.role === "user" ? "あなた" : "AI"}:</strong> {msg.content}
            </div>
          ))
        )}
      </div>

      {/* 入力フォーム */}
      <div className="flex gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !isLoading) {
              handleSendMessage();
            }
          }}
          placeholder="質問や企画案を入力..."
          disabled={isLoading}
          className="flex-1 rounded border border-neutral-300 px-3 py-2 text-sm disabled:bg-neutral-100"
        />
        <button
          onClick={handleSendMessage}
          disabled={isLoading || !input.trim()}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {isLoading ? "送信中…" : "送信"}
        </button>
      </div>
    </div>
  );
}
```

#### `web/src/app/projects/[id]/plan/ArticlePlanProposals.tsx` (新規)

```typescript
"use client";

import { useState } from "react";
import { suggestArticlePlanTitles } from "@/lib/apiClient";
import { PlanChatMessage } from "@/lib/apiClient";

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
    } catch (error) {
      alert("タイトル提案に失敗しました: " + (error instanceof Error ? error.message : String(error)));
    } finally {
      setIsLoading(false);
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

      <button
        onClick={handleSuggestTitles}
        disabled={isLoading || initialHistory.length === 0}
        className="mb-4 rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {isLoading ? "提案取得中…" : "タイトル提案を取得"}
      </button>

      {titles.length === 0 ? (
        <p className="text-sm text-neutral-500">タイトル提案はまだありません。チャットで壁打ちしてから取得してください。</p>
      ) : (
        <div className="space-y-2">
          {titles.map((title, idx) => (
            <label key={idx} className="flex items-center gap-2 rounded border border-neutral-200 p-3 hover:bg-neutral-50">
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
      )}

      <button
        disabled={selected.size === 0}
        className="mt-4 w-full rounded bg-green-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        選択した記事 ({selected.size} 件) の計画を受け入れる
      </button>
    </div>
  );
}
```

#### `web/src/app/projects/[id]/plan/actions.ts` (新規)

```typescript
"use server";

import { revalidatePath } from "next/cache";
import { sendArticlePlanChatMessage, suggestArticlePlanTitles } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { PlanChatMessage } from "@/lib/apiClient";

export interface SendChatMessageState {
  error?: string;
}

export async function sendPlanChatMessageAction(
  projectId: number,
  _prevState: SendChatMessageState,
  history: PlanChatMessage[],
  message: string
): Promise<SendChatMessageState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    await sendArticlePlanChatMessage(projectId, { history, message }, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}/plan`);
  return {};
}

export interface SuggestTitlesState {
  error?: string;
}

export async function suggestPlanTitlesAction(
  projectId: number,
  _prevState: SuggestTitlesState,
  history: PlanChatMessage[]
): Promise<SuggestTitlesState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    await suggestArticlePlanTitles(projectId, { history }, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}/plan`);
  return {};
}
```

#### `web/src/lib/apiClient.ts` に追加

**型追加**:

```typescript
export interface PlanChatMessage {
  role: "user" | "assistant";
  content: string;
}

export interface PlanChatResponse {
  reply: string;
}

export interface SuggestTitlesResponse {
  titles: string[];
}
```

**関数追加**:

```typescript
export function sendArticlePlanChatMessage(
  projectId: number,
  data: { history: PlanChatMessage[]; message: string },
  actor?: ActorInfo
): Promise<PlanChatResponse> {
  return apiFetch<PlanChatResponse>(`/api/projects/${projectId}/article-plan/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
    actor,
  });
}

export function suggestArticlePlanTitles(
  projectId: number,
  data: { history: PlanChatMessage[] },
  actor?: ActorInfo
): Promise<SuggestTitlesResponse> {
  return apiFetch<SuggestTitlesResponse>(
    `/api/projects/${projectId}/article-plan/suggest-titles`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
      actor,
    }
  );
}
```

#### `web/src/app/projects/page.tsx` に追加

```typescript
{/* 既存の行アクション列に「計画」リンク追加 */}
<td className="px-4 py-2 text-right space-x-2">
  <Link href={`/projects/${project.id}/plan`} className="text-sm text-neutral-600 hover:underline">
    計画
  </Link>
  <Link href={`/projects/${project.id}`} className="text-sm text-neutral-600 hover:underline">
    詳細
  </Link>
</td>
```

## スコープ・実装項目

実装対象:

- [ ] DTO: `PlanChatMessage`, `PlanChatRequest`, `PlanChatResponse`, `SuggestTitlesRequest`, `SuggestTitlesResponse` 新規作成
- [ ] Service: `ArticlePlanService` 新規(Ollama 呼び出し、GenerationJob 記録、JSON 抽出)
- [ ] Controller: `ArticlePlanController` 新規(2 エンドポイント)
- [ ] フロント: `page.tsx`, `ArticlePlanChat.tsx`, `ArticlePlanProposals.tsx`, `actions.ts` 新規作成、既存`projects/page.tsx`に「計画」リンク追加
- [ ] API Client: `apiClient.ts` に型・関数追加
- [ ] テスト: Unit テスト整備

対象外・スコープ外:

- チャット履歴の永続化/再開
- ストリーミングレスポンス対応
- プロンプトカスタマイズ(ユーザー/プロジェクトごとの指示調整)
- タイトル提案の再生成・内容編集

## 実装順序

1. DTO 作成
2. Service / Controller 実装・ユニットテスト
3. フロントエンド実装
4. 実機検証

## テスト整備

### Unit Tests

**`ArticlePlanServiceTest`**:

- `chat()` 呼び出しでプロンプトが正しく構築されることを検証(履歴が含まれる、System 指示がプリペンドされる等)
- Ollama 呼び出し時、戻り値が `PlanChatResponse` に正しく変換されることを検証
- `suggestTitles()` 呼び出しでタイトル指示がプロンプトに含まれることを検証
- JSON 抽出が「`[`〜`]`」範囲に限定されること、失敗時に空配列を返すことを検証
- タイトル件数が 5 件を超える場合、制限されることを検証
- 生成ジョブが `type="plan_chat"` / `"plan_suggest_titles"`で記録されることを検証
- エラー発生時に `failJob()` が呼ばれ、ジョブが `"failed"`状態で記録されることを検証

### 実機検証

- システム画面で GitHub トークンを設定、プロジェクトにリポジトリを紐付け
- 計画ページでチャットを複数ターン繰り返し、AI の応答が日本語で適切に返ることを確認
- 壁打ちの途中でタイトル提案を取得し、最大 5 件のタイトル案が JSON 形式で返ることを確認
- 不正な JSON が Ollama から返された場合(あるいはタイトル形式以外)、空リストで graceful に落ちることを確認

## 未決事項

- タイトル提案の結果が 0 件だった場合、UI 上で「もう一度お試しください」等のメッセージ表示の有無
- Ollama の応答がタイムアウト・エラーの場合、UI でのエラーメッセージの詳細度(一般的なエラーか、Ollama 固有の詳細情報を含めるか)
