# Features and Usage Guide

This guide covers the key features of Let's Blog Server and how to use them effectively.

## Table of Contents

- [VSCode Extension](#vscode-extension)
- [Web Admin Panel](#web-admin-panel)
- [AI Writing Features](#ai-writing-features)
- [Image Generation](#image-generation)
- [Multi-site Publishing](#multi-site-publishing)
- [Article Management](#article-management)

## VSCode Extension

### Overview

The VSCode extension is your primary tool for writing and publishing articles. It seamlessly integrates with Let's Blog Server to provide a distraction-free writing experience.

**Screenshot placeholder: VSCode with Let's Blog extension sidebar**

### Creating a New Article

1. Open VSCode
2. Look for the **Let's Blog** icon in the left sidebar
3. Click on it to open the Let's Blog panel
4. Click **New Article** button
5. The extension creates a new Markdown file with template

**Screenshot placeholder: New article creation dialog**

### Article Metadata

Every article includes metadata at the top (YAML front matter):

```markdown
---
title: My First Article
description: A brief summary for social media
tags: 
  - technology
  - tutorial
date: 2026-08-08
featured_image: ""
---

# Article content starts here...
```

### Writing with AI Assistance

#### Draft Generation

If you're starting from a topic outline:

1. Place cursor at the section where you want content
2. Press `Ctrl+Shift+G` (or use menu: **Let's Blog** → **Generate with AI**)
3. Enter your prompt or outline
4. The AI generates initial draft text

**Screenshot placeholder: AI draft generation in action**

#### Proofreading

Get AI-powered suggestions for grammar and clarity:

1. Select text to proofread
2. Press `Ctrl+Shift+P` (or **Let's Blog** → **Proofread Selection**)
3. Review suggestions inline

**Screenshot placeholder: Proofreading suggestions overlay**

#### Tag Suggestions

Generate relevant tags based on content:

1. Use menu: **Let's Blog** → **Suggest Tags**
2. Review the suggestions
3. Accept/reject each tag

### Publishing an Article

1. When article is ready, click **Publish** in the Let's Blog panel
2. Select target site(s) from the list
3. Choose post status:
   - **Draft** - Save as unpublished draft
   - **Publish** - Publish immediately
   - **Schedule** - Set publication date/time
4. Click **Publish to WordPress**

**Screenshot placeholder: Multi-site selection during publish**

The extension shows real-time progress for each site.

### Managing Published Articles

View and manage previously published articles:

1. Click **My Articles** in the Let's Blog panel
2. Filter by:
   - **Status** - Draft/Published/Scheduled
   - **Site** - Filter by WordPress site
   - **Date range** - Find articles from specific period
3. Click any article to open it
4. Use **Update on WordPress** to modify published content

**Screenshot placeholder: Article list with filters**

## Web Admin Panel

### Accessing the Admin Panel

Navigate to `https://localhost` in your browser and log in with your credentials.

**Screenshot placeholder: Web admin login page**

### Dashboard

The dashboard provides an overview of your blog system:

- **Recent Posts** - Latest articles published
- **System Status** - Health of all services
- **Scheduled Posts** - Upcoming publications
- **Quick Statistics** - Post count, site count

**Screenshot placeholder: Admin dashboard overview**

### Site Management

#### Adding WordPress Sites

1. Navigate to **Settings** → **Websites**
2. Click **Add Website**
3. Choose the connection method (auto-provisioned, or SSH) and fill in:
   - **Site Name** - Friendly name for reference
   - **WordPress URL** - Full URL of WordPress installation
   - **SSH Host / SSH User / WordPress Path / SSH Key** - Connection details for an SSH site
     (not needed for an auto-provisioned site)
4. Click **Test Connection**
5. Click **Save**

**Screenshot placeholder: Site configuration form with validation**

#### Managing Credentials

1. Go to **Settings** → **Websites**
2. Find the site in the list
3. Click the **Edit** button to update credentials
4. Or click **Remove** to disconnect the site

#### Site Statistics

Each site shows:
- Total posts published
- Last publish date
- Connection status
- Synced categories and tags

## AI Writing Features

### Understanding AI Capabilities

The default AI provider is the bundled **Ollama** container (`LLM_PROVIDER=OLLAMA`), so AI writing
assistance works locally without an external API key. You can switch to `OPENAI` or `CLAUDE` with
`LLM_PROVIDER` in `.env` (a value saved on the system settings screen takes precedence).

With Ollama, choose your model based on your hardware by setting `LLM_OLLAMA_MODEL` in `.env`
(the default model is pulled automatically at startup by `ollama-model-init`):

| Model | Size | Speed | Quality | Use Case |
|-------|------|-------|---------|----------|
| `qwen2.5:3b-instruct` | 3.1B params | Very Fast | Basic | Quick drafts, summaries |
| `qwen2.5:7b-instruct` (Default) | 7.6B params | Fast | Good | General writing, balanced |
| `qwen2.5:14b-instruct` | 14.8B params | Moderate | Excellent | Complex topics, precision |
| `qwen2.5:32b-instruct` | 32.2B params | Slow | Expert | Deep technical content |

### Using AI Features Effectively

#### Drafting an Outline

Provide a topic and let AI generate structure:

**Prompt:** "Generate an outline for an article about Docker security best practices"

Expected output:
```
1. Introduction to Docker security risks
2. Image security hardening
   2.1. Base image selection
   2.2. Layer caching vulnerabilities
3. Runtime security
4. Network security
5. Secret management
6. Monitoring and logging
7. Conclusion
```

#### Expanding Sections

Place cursor in a section and request expansion:

**Prompt:** "Expand section 2.1 with practical examples and commands"

#### Generating Variations

Get multiple versions of the same content:

**Prompt:** "Write 3 different introductions for this article about React performance"

The AI will generate options; select the one you prefer.

### Best Practices for AI

- **Be specific** - Detailed prompts produce better results
- **Iterate** - Regenerate if output doesn't match expectations
- **Review and edit** - AI is a tool; always verify factual accuracy
- **Use for starting points** - Don't rely solely on AI output
- **Reference existing content** - Provide context for consistency

## Image Generation

Let's Blog generates images using a self-hosted ComfyUI instance. Both the VSCode extension and
the Web Admin Panel expose the same underlying capability, with a chat-assisted workflow for
turning a rough idea into a usable ComfyUI prompt before generating.

### Chat-to-Image Workflow

Instead of hand-writing a ComfyUI-style prompt, you can describe what you want in plain language
and let the selected AI provider (Ollama by default) turn it into an English prompt:

1. Open the image generation UI (VSCode: see below; Web Admin: the **AI・アセット** tab on a
   project's detail page → **アセット画像生成** → **チャットでプロンプトを作成**).
2. Type a description (e.g. "夕焼けの海辺を歩く猫") into the chat box and send it.
3. The generated prompt automatically fills the **prompt** field of the parameter form below —
   you can still hand-edit it, or send another chat message to refine it further.
4. Adjust the remaining parameters (negative prompt, steps, CFG scale, seed, sampler, scheduler,
   width/height, batch size, checkpoint, LoRA) as needed, then generate.

This calls `POST /api/projects/{projectId}/ai/generate-image-prompt` with the chat history and
your latest message, and returns `{ "prompt": "<generated prompt>" }`. Each call is recorded as a
`generation_jobs` entry (type `llm_image_prompt`) for auditing.

### Generating Images (VSCode Extension)

1. With a Markdown article open, run **Let's Blog: Generate Image** from the Command Palette, or
   right-click in the editor and choose it from the context menu. There is no default keybinding
   for this command.
2. Optionally use the chat box (see above) to generate a starting prompt. Plain **Enter** in the
   chat box inserts a newline; **Ctrl+Enter** (**Cmd+Enter** on macOS) sends the message.
3. Review/adjust the parameter form, then click **生成** or press **Ctrl+Enter**/**Cmd+Enter**
   anywhere else in the panel to generate. **batch size** (max 16) is how many images one
   generation produces; **batch count** (max 16) is how many times that generation is repeated
   with a fresh seed, so a request asks for `batchSize × batchCount` images (up to 256). There is
   no cap on the total, but the time grows in proportion — 256 images take a very long time.
4. On the result, click **アイキャッチとして設定** to save the image under `assets/` and set it as
   the article's `featured_image` in front matter, or **アセットとして追加** to save it under
   `assets/` and insert a Markdown image reference at the cursor.
5. **Escape** cancels an in-flight chat or image-generation request.

This calls `POST /api/ai/image` with the prompt and parameters (`projectId` is a field in the
request body, not part of the URL), and returns `batchSize × batchCount` base64-encoded images
plus a persisted image ID for each. The extension raises its own request timeout to match the
server's budget for the requested number of images (capped at nginx's 3600s), so a large batch is
not cut off client-side while the server is still generating; `letsBlog.requestTimeoutMs` still
wins when it is set higher.

### Generating Images (Web Admin Panel)

1. Open a project, go to the **AI・アセット** tab, and click **アセット画像生成** to expand the
   panel (this also loads the checkpoint/sampler/scheduler/LoRA options and the project's default
   image size).
2. Use **チャットでプロンプトを作成** (see the chat-to-image workflow above) to fill the prompt,
   or type one directly.
3. Adjust parameters and click **生成**. `batchSize × batchCount` result thumbnails appear in a
   grid; click one to select it.
4. Click **アセットとして追加(全環境へアップロード)** to upload the selected image as a project
   asset to every configured environment (local/test/production).

### Managing Generated Images

All generated images (from either the extension or the Web Admin Panel) are listed on the
**生成画像ギャラリー** page (`/image-gallery` in the Web Admin Panel), which shows the prompt,
auto-suggested tags, and generation date for each image, and lets you filter by tag, edit tags,
or delete an image. The VSCode extension's **Let's Blog: Image Gallery** panel offers the same
list for inserting a previously generated image into the current article, plus a right-click
**「この設定で画像生成」** option that reopens the Generate Image panel pre-filled with that
image's original settings.

## Multi-site Publishing

### Single-Article Multi-Site Publish

Publish one article to multiple WordPress sites simultaneously:

**Screenshot placeholder: Multi-site selection during publish**

1. Write article in VSCode
2. Click **Publish**
3. **Multi-site Select** - Check boxes for target sites
4. If sites need different configurations:
   - **Category mapping** - Assign article to different categories per site
   - **Tag mapping** - Use site-specific tags
5. Click **Publish to WordPress**

### Campaign Publishing

Publish related articles across sites as a coordinated campaign:

1. Navigate to **Publishing** → **Campaigns**
2. Click **New Campaign**
3. Name the campaign (e.g., "August Tech Series")
4. Add articles to the campaign
5. Select target sites
6. Set publication schedule
7. Click **Schedule Campaign**

**Screenshot placeholder: Campaign creation and scheduling**

### Post Sync Status

Monitor publication status across sites:

1. Go to **My Articles**
2. Click an article
3. See **Publication Status** section showing:
   - Which sites have the article
   - Publication date on each site
   - Whether article is in sync across sites

## Article Management

### Search and Filter

Find articles quickly:

**Screenshot placeholder: Article search interface**

- **Full-text search** - Search title, content, tags
- **Date range** - Filter by publication date
- **Status filter** - Show Draft/Published/Scheduled
- **Site filter** - Filter by WordPress site
- **Tag filter** - Show articles with specific tags

### Batch Operations

Manage multiple articles at once:

1. Select articles (checkboxes on the left)
2. Choose bulk action:
   - **Add Tags** - Add tags to selected
   - **Set Category** - Assign category to all
   - **Change Status** - Draft/Publish/Schedule
   - **Delete** - Remove selected

**Screenshot placeholder: Bulk operations toolbar**

### Article Revisions

Every edit to an article is automatically saved:

1. Click an article
2. Go to **History** tab
3. See all versions with timestamps
4. Click any version to view
5. Click **Restore** to revert to earlier version

**Screenshot placeholder: Revision history timeline**

## System Settings

### User Management

Manage user accounts in **Settings** → **Users**:

- **Create new users** - Add team members
- **Set permissions** - Admin/Editor/Viewer roles
- **Manage access** - Enable/disable accounts

### System Maintenance

Monitor and maintain system health:

- **Logs** - View system event logs
- **Performance** - Monitor CPU, memory, disk usage
- **Backups** - Schedule and manage database backups
- **Service Status** - Check status of all components

**Screenshot placeholder: System health dashboard**

---

## Keyboard Shortcuts (VSCode Extension)

| Action | Windows/Linux | macOS |
|--------|---------------|-------|
| New Article | `Ctrl+Alt+N` | `Cmd+Option+N` |
| Publish | `Ctrl+Alt+P` | `Cmd+Option+P` |
| Send chat / Generate image (Generate Image panel) | `Ctrl+Enter` | `Cmd+Enter` |
| Proofread | `Ctrl+Shift+P` | `Cmd+Shift+P` |
| Preview | `Ctrl+Shift+V` | `Cmd+Shift+V` |

## Troubleshooting Features

- **Connection Test** - Verify WordPress site connectivity
- **API Health Check** - Test server API availability
- **Sync Status** - Check article synchronization across sites
- **Error Logs** - View detailed error messages

For detailed troubleshooting, see [Comprehensive Troubleshooting Guide](COMPREHENSIVE_TROUBLESHOOTING.md).

---

Next: [Video Tutorials](VIDEO_TUTORIALS_GUIDE.md) | [Article Best Practices](ARTICLE_AUTHORING_BEST_PRACTICES.md)
