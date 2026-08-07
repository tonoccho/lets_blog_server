# Penpot × Ollama MCP サーバー設計書

## 概要

Penpot（デザインツール）と Ollama（LLM）を MCP（Model Context Protocol）サーバーを通じて連携させ、AI 支援デザイン機能を実現。

---

## 1. アーキテクチャ概要

### 1.1 全体構成

```
┌─────────────────────────────────────────────────────────────┐
│ Penpot (Web UI)                                             │
│  └─ Penpot Plugin (AI Design Assistant Panel)              │
│       ↓ HTTP/JSON リクエスト                                │
├─────────────────────────────────────────────────────────────┤
│ MCP Server (Node.js / Python)                               │
│  ├─ Tool: design_suggestion                                 │
│  ├─ Tool: improve_component                                 │
│  ├─ Tool: generate_color_palette                            │
│  └─ Tool: suggest_typography                                │
│       ↓ HTTP リクエスト                                      │
├─────────────────────────────────────────────────────────────┤
│ Ollama API (LLM Service)                                    │
│  └─ Model: qwen2.5:7b-instruct                             │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 通信フロー

```
1. Penpot Plugin ユーザーがデザイン提案をリクエスト
   ↓
2. Penpot Plugin が MCP サーバーへ JSON リクエスト送信
   POST /api/design-suggestion
   {
     "design_brief": "ボタンコンポーネント, 青系, 大サイズ",
     "context": { "project": "Let's Blog", "brand": "modern" }
   }
   ↓
3. MCP サーバーが Ollama へプロンプト送信
   POST http://ollama:11434/api/generate
   {
     "model": "qwen2.5:7b-instruct",
     "prompt": "[デザイン提案プロンプト] ボタンコンポーネント, 青系, ...",
     "stream": false
   }
   ↓
4. Ollama が LLM 推論結果を返す
   { "response": "デザイン提案: 背景色 #0066CC, ..." }
   ↓
5. MCP サーバーが結果を Penpot Plugin に返す
   { "suggestion": "...", "svg": "...", "css": "..." }
   ↓
6. Penpot Plugin がキャンバスへ自動配置
```

---

## 2. MCP サーバーの責務

### 2.1 Tool インターフェース定義

#### Tool 1: `design_suggestion`

**目的:** デザイン要件から初期デザイン案を生成

**入力:**
```json
{
  "design_brief": "string",      // 例: "ボタンコンポーネント, プライマリアクション用, 青系"
  "context": {
    "project": "string",          // 例: "Let's Blog Server"
    "brand": "string",            // 例: "modern", "minimal", "bold"
    "audience": "string"          // 例: "developers", "general", "enterprise"
  }
}
```

**出力:**
```json
{
  "suggestion": "string",        // テキスト形式の提案
  "properties": {
    "colors": ["#0066CC", "#ffffff"],
    "typography": { "font": "Inter", "size": 16, "weight": 500 },
    "spacing": { "padding": "12px 16px", "borderRadius": "8px" },
    "shadow": "0 2px 8px rgba(0,0,0,0.1)"
  },
  "css": "string",               // CSS スニペット
  "figma_shorthand": "string"    // Penpot 適用用ショートハンド
}
```

**Ollama プロンプト例:**
```
You are an expert UI/UX designer for modern web applications. 
Generate a design proposal for the following component:

Brief: {design_brief}
Project: {project}
Brand Style: {brand}
Target Audience: {audience}

Provide:
1. Color palette (hex codes)
2. Typography (font family, size, weight)
3. Spacing (padding, margin, gap)
4. Shadow/depth
5. Border radius and other styling

Format as JSON with clear property names.
```

---

#### Tool 2: `improve_component`

**目的:** 既存コンポーネントの改善提案

**入力:**
```json
{
  "component_name": "string",           // 例: "PrimaryButton"
  "current_design": "string",           // 現在の CSS/スタイル定義
  "improvement_areas": ["string"],      // 例: ["accessibility", "mobile-responsiveness", "dark-mode"]
  "design_context": "string"            // 例: "dark-mode support needed"
}
```

**出力:**
```json
{
  "improvements": [
    {
      "area": "string",                 // 改善対象
      "issue": "string",                // 問題点
      "suggestion": "string",           // 改善案
      "css_change": "string"            // CSS 変更例
    }
  ],
  "overall_score_before": 7.5,
  "overall_score_after": 9.2
}
```

**Ollama プロンプト例:**
```
You are a design system expert reviewing a UI component.

Component: {component_name}
Current CSS:
{current_design}

Review focus areas: {improvement_areas}
Context: {design_context}

Provide specific, actionable improvements:
- Identify accessibility issues (WCAG)
- Suggest responsive design enhancements
- Dark mode compatibility
- Performance optimizations

Format as structured JSON.
```

---

#### Tool 3: `generate_color_palette`

**目的:** ブランドガイドラインから配色パレット生成

**入力:**
```json
{
  "brand_description": "string",       // 例: "tech startup, innovative, trustworthy"
  "base_color": "string",              // 例: "#0066CC"
  "accessibility": true,               // WCAG AA/AAA 準拠
  "dark_mode_support": true
}
```

**出力:**
```json
{
  "primary": "#0066CC",
  "primary_light": "#3399FF",
  "primary_dark": "#003399",
  "secondary": "#FF6633",
  "success": "#00AA44",
  "warning": "#FFAA00",
  "error": "#FF3333",
  "neutral_100": "#FFFFFF",
  "neutral_200": "#F5F5F5",
  "neutral_300": "#E0E0E0",
  "neutral_400": "#999999",
  "neutral_500": "#666666",
  "neutral_600": "#333333",
  "neutral_700": "#1A1A1A",
  "dark_mode": {
    "primary": "#66CCFF",
    "neutral_100": "#1A1A1A",
    // ... dark mode colors
  },
  "contrast_ratios": {
    "primary_on_white": 5.2,  // WCAG AA
    "primary_on_light": 4.8   // WCAG AA
  }
}
```

**Ollama プロンプト例:**
```
You are a color theory and accessible design expert.

Brand Description: {brand_description}
Base Color: {base_color}
Requirements:
- WCAG AA accessibility minimum
- Dark mode support
- Professional appearance

Generate a complete color palette including:
1. Primary, secondary, accent colors
2. Neutral grays (100-700)
3. Status colors (success, warning, error)
4. Dark mode variants
5. Verify contrast ratios

Format as JSON with hex codes and contrast ratio calculations.
```

---

#### Tool 4: `suggest_typography`

**目的:** コンテンツ・デザインコンテキストからタイポグラフィ提案

**入力:**
```json
{
  "design_context": "string",        // 例: "web admin dashboard"
  "content_type": "string",          // 例: "body text", "heading", "label"
  "accessibility": true,
  "locale": "string"                 // 例: "ja", "en"
}
```

**出力:**
```json
{
  "recommendations": [
    {
      "name": "Heading 1",
      "font_family": "Inter",
      "font_size": 32,
      "font_weight": 700,
      "line_height": 1.2,
      "letter_spacing": "-0.5px",
      "css": "font-family: 'Inter', sans-serif; font-size: 32px; font-weight: 700; line-height: 1.2;"
    },
    {
      "name": "Body",
      "font_family": "Inter",
      "font_size": 14,
      "font_weight": 400,
      "line_height": 1.6,
      "letter_spacing": "0px"
    }
  ],
  "fallback_fonts": ["Segoe UI", "Roboto", "sans-serif"],
  "loading_strategy": "swap"  // font-display: swap
}
```

**Ollama プロンプト例:**
```
You are a typography expert for web design.

Design Context: {design_context}
Content Type: {content_type}
Accessibility Requirement: WCAG {accessibility}
Language/Locale: {locale}

Recommend typography including:
1. Font families (with web-safe fallbacks)
2. Font sizes and weights for hierarchy
3. Line heights and letter spacing for readability
4. Japanese font handling if locale=ja
5. Accessibility considerations (size, contrast, readability)

Format as JSON with CSS properties.
```

---

## 3. Ollama 連携戦略

### 3.1 モデル選定

| 項目 | 値 |
|------|-----|
| モデル | `qwen2.5:7b-instruct` |
| コンテキスト長 | 8K トークン（充分） |
| 推論時間 | 3-10秒/リクエスト |
| メモリ | 4-8GB VRAM |

### 3.2 プロンプトエンジニアリング

各 Tool のプロンプトは以下の構造を採用：

```
[Role Definition]
You are a [expert role].

[Task Description]
[具体的なタスク説明]

[Input]
[入力パラメータ]

[Requirements]
[制約・要件]

[Output Format]
Format as JSON with [構造]
```

### 3.3 キャッシング・パフォーマンス

**初回リクエスト:**
```
Request → Ollama inference → Response (3-10s)
```

**改善案（オプション）:**
- デザイン提案のキャッシング（同一ブリーフに対して）
- プロンプトテンプレートのキャッシング
- Ollama 側の KV キャッシュ活用

---

## 4. エラーハンドリング・復帰戦略

### 4.1 エラーケース

| シナリオ | 対応 |
|---------|------|
| Ollama 接続エラー | 明示的なエラーメッセージ + リトライ提案 |
| タイムアウト（>30s） | キャンセル + フォールバック提案 |
| 無効な LLM 出力 | JSON パース失敗時は再プロンプト or 例外 |
| メモリ不足 | Ollama ログにて確認 + モデル切り替え検討 |

### 4.2 ログ・モニタリング

```
[timestamp] INFO  Tool: design_suggestion | Brief: "..." | Status: Processing
[timestamp] INFO  Ollama request | Model: qwen2.5:7b-instruct | Tokens: 120
[timestamp] INFO  Ollama response | Duration: 4.2s | Output tokens: 250
[timestamp] INFO  Tool: design_suggestion | Status: Success | Result: {...}
```

---

## 5. テスト戦略

### 5.1 Unit Test

各 Tool の入出力を検証：

```python
def test_design_suggestion_valid_input():
    result = design_suggestion(
        design_brief="Primary button, blue",
        context={"project": "Let's Blog", "brand": "modern"}
    )
    assert "properties" in result
    assert "colors" in result["properties"]

def test_design_suggestion_invalid_input():
    with pytest.raises(ValueError):
        design_suggestion(design_brief="", context={})
```

### 5.2 Integration Test

Ollama 連携をテスト：

```python
def test_ollama_connectivity():
    """Ollama が起動・応答可能か確認"""
    response = requests.get("http://ollama:11434/api/tags")
    assert response.status_code == 200
    assert "models" in response.json()

def test_design_suggestion_with_ollama():
    """実際の Ollama 推論テスト"""
    result = design_suggestion(...)
    assert isinstance(result, dict)
    assert "suggestion" in result
```

### 5.3 Performance Test

レスポンス時間を測定：

```python
def test_design_suggestion_performance():
    start = time.time()
    result = design_suggestion(...)
    duration = time.time() - start
    assert duration < 30  # 30秒以内
```

---

## 6. セキュリティ・プライバシー

### 6.1 入力検証

```python
# デザイン brief の最大長制限
MAX_BRIEF_LENGTH = 500
assert len(design_brief) <= MAX_BRIEF_LENGTH

# コンテキスト値の許可リスト
ALLOWED_BRANDS = ["modern", "minimal", "bold", "elegant"]
assert context["brand"] in ALLOWED_BRANDS
```

### 6.2 プロンプトインジェクション対策

```python
# ユーザー入力を直接プロンプトに埋め込まない
# ❌ 悪い例: f"Brief: {design_brief}"
# ✅ 良い例: prompt_template.format(brief=escape_prompt(design_brief))

def escape_prompt(text: str) -> str:
    """プロンプトインジェクション対策"""
    # ユーザー入力内の制御文字を削除
    return text.replace("\\", "").replace('"', '\\"').replace("\n", " ")
```

### 6.3 Ollama 認証（オプション）

デフォルトではローカルネットワーク内で認証不要。本番環境では以下を検討：
- API キーベース認証
- リバースプロキシでの認証層追加

---

## 7. パフォーマンス最適化

### 7.1 リクエストキューイング

複数リクエストが同時に来た場合、キューイング：

```python
from asyncio import Queue

design_queue = Queue(maxsize=10)

async def handle_design_request(brief, context):
    await design_queue.put((brief, context))
    result = await process_design_queue()
    return result
```

### 7.2 キャッシング（オプション）

```python
from functools import lru_cache

@lru_cache(maxsize=100)
def get_color_palette(brand_description: str, base_color: str):
    """同一パラメータの結果をキャッシュ"""
    return generate_color_palette(brand_description, base_color)
```

---

## 8. デプロイメント・スケーリング

### 8.1 Docker イメージ

```dockerfile
FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY . .
EXPOSE 3000
CMD ["node", "src/server.js"]
```

### 8.2 環境変数

```bash
OLLAMA_BASE_URL=http://ollama:11434
MCP_PORT=3000
MCP_LOG_LEVEL=info
PENPOT_API_URL=http://penpot:80
```

### 8.3 リソース要件

| 項目 | 要件 |
|------|------|
| CPU | 1-2 コア |
| メモリ | 512MB-1GB |
| ディスク | 100MB（アプリケーション） |

---

## 9. 今後の拡張

- [ ] キャッシング機構（Redis 統合）
- [ ] メトリクス収集（Prometheus）
- [ ] ユーザーフィードバック機構（提案品質の学習）
- [ ] 複数 Ollama モデルのサポート
- [ ] Brave Search 連携（デザイントレンド検索）
- [ ] 異なるデザインスタイル専門の LLM Fine-tuning

---

## 参考資料

- MCP Protocol: https://github.com/anthropics/mcp
- Ollama API: https://github.com/ollama/ollama/blob/main/docs/api.md
- Penpot API: https://help.penpot.app/
- Design System リソース: https://www.nngroup.com/articles/design-systems-101/
