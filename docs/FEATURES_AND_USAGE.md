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
3. Fill in:
   - **Site Name** - Friendly name for reference
   - **WordPress URL** - Full URL of WordPress installation
   - **Username** - WordPress admin username
   - **Application Password** - Generated from WordPress
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

Let's Blog Server uses **Ollama** for local AI processing. Choose your model based on your hardware:

| Model | Size | Speed | Quality | Use Case |
|-------|------|-------|---------|----------|
| `qwen2.5:3b` | 3.1B params | Very Fast | Basic | Quick drafts, summaries |
| `qwen2.5:7b` (Default) | 7.6B params | Fast | Good | General writing, balanced |
| `qwen2.5:14b` | 14.8B params | Moderate | Excellent | Complex topics, precision |
| `qwen2.5:32b` | 32.2B params | Slow | Expert | Deep technical content |

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

### Featured Image Generation

Let's Blog automatically generates featured images for articles using ComfyUI:

1. Write your article
2. In the VSCode extension, click **Generate Featured Image**
3. Enter a description of desired visual (or use AI suggestion)

**Screenshot placeholder: Image generation interface**

4. The system generates 2-3 options
5. Click to preview each
6. Select your favorite
7. Click **Use as Featured Image**

### Supported Image Styles

- **Blog header** - Horizontal format for article headers
- **Social media** - Square format for sharing
- **Thumbnail** - Small format for lists
- **Custom** - Specify dimensions (width × height in pixels)

### Image Customization

If the generated image isn't perfect:

1. Click **Regenerate** for different variations
2. Adjust the description and regenerate
3. Download the image and edit manually

**Screenshot placeholder: Image generation options grid**

### Managing Generated Images

Generated images are stored in the article:

1. Navigate to **Media** in the admin panel
2. Filter by **Source** → **AI Generated**
3. View, download, or delete images

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

### API Configuration

For advanced users integrating with external tools:

1. Navigate to **Settings** → **API**
2. View your API key
3. Click **Generate New Key** to rotate key
4. Use key in header: `X-API-Key: your_key_here`

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
| AI Draft | `Ctrl+Shift+G` | `Cmd+Shift+G` |
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
