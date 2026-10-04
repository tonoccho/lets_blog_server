# Video Tutorials Guide

This guide outlines recommended video tutorials for learning Let's Blog Server. While detailed instructions are in written guides, video tutorials provide a visual walkthrough of key workflows.

## Recommended Video Structure

### Tier 1: Essential (First-Time Setup)

These videos should be watched in order during initial setup.

#### 1. System Overview (5-7 minutes)

**Title:** "Let's Blog Server: Complete Overview"

**Content:**
- Project purpose and capabilities
- System architecture (high-level)
- Key components:
  - VSCode extension for writing
  - Web admin panel for management
  - Docker services running in background
- Typical workflow from article to publication

**Visual Aids:**
- Architecture diagram
- Screenshot montage of key interfaces
- Short clips of actual usage

**Target Audience:** Complete beginners, decision-makers

---

#### 2. Initial Setup & Docker Configuration (10-12 minutes)

**Title:** "Getting Started: Setting Up Let's Blog Server"

**Content:**
1. **Prerequisites check**
   - System requirements verification
   - Installing Git, Docker, NVIDIA tools
   - Testing installations (screenshots)

2. **Cloning repository** (1 min)
   ```bash
   git clone <url>
   cd lets_blog_server
   ```

3. **Configuration** (3 min)
   - Copying .env.example to .env
   - Explaining critical fields:
     - MySQL passwords
     - SERVER_API_KEY (API authentication)
     - APP_ENCRYPTION_KEY generation
     - NEXTAUTH_SECRET generation
   - Save and close

4. **Certificate generation** (2 min)
   ```bash
   bash scripts/generate-certs.sh
   ```
   - Explain self-signed certificate
   - Show generated files

5. **Starting services** (2 min)
   ```bash
   docker compose up -d
   ```
   - Show real-time log output
   - Monitor each service starting
   - Verify all services running: `docker compose ps`

6. **Accessing web panel** (2 min)
   - Open browser
   - Handle SSL warning
   - Show initial login page

**Visual Style:**
- Screen recording with cursor highlighting
- Terminal output with clear explanations
- B-roll of Docker logs
- Annotations for key settings

**Equipment Needed:**
- Desktop screen recording
- Text editor visible
- Terminal with clear font

---

#### 3. Creating Admin Account & Adding WordPress Sites (8-10 minutes)

**Title:** "Let's Blog Server: Admin Setup & WordPress Configuration"

**Content:**
1. **Admin account creation** (3 min)
   - Access setup page
   - Fill in registration form
   - Create strong password best practices

2. **WordPress site connection** (5 min)
   - Navigate to Sites settings
   - Step-by-step site configuration:
     - Site name
     - WordPress URL
     - Connection method (auto-provisioned or SSH)
     - SSH host, SSH user, WordPress path and SSH key (SSH connection only)
   - Test connection
   - Verify success

3. **Dashboard overview** (2 min)
   - Show main dashboard
   - Identify key panels
   - Quick orientation

**Screenshots/Video Segments:**
- WordPress admin panel (showing the SSH key pair registration)
- Form filling with validation
- Connection success message

---

### Tier 2: Core Workflows

These videos cover the main usage patterns.

#### 4. Publishing Your First Article (12-15 minutes)

**Title:** "Let's Blog Server: Publishing Your First Article"

**Prerequisites:** Admin account created, WordPress site configured, VSCode extension installed

**Content:**

1. **VSCode Extension Overview** (2 min)
   - Show extension in sidebar
   - Identify Let's Blog panel
   - Key buttons and menus

2. **Creating new article** (3 min)
   - Click "New Article"
   - Show Markdown template with metadata
   - Explain front matter:
     - Title
     - Description (SEO)
     - Tags
     - Featured image field

3. **Writing content** (4 min)
   - Type article title
   - Write a sample article section by section
   - Show Markdown syntax highlighting
   - Mention live preview

4. **Publishing workflow** (4 min)
   - Click Publish button
   - Select target sites
   - Choose post status (Draft/Publish/Schedule)
   - Monitor publication progress
   - Show success confirmation
   - Verify post appears on WordPress

5. **Accessing published article** (2 min)
   - Visit WordPress site
   - Show published article
   - Verify content integrity

**Visual Style:**
- Split-screen: VSCode + WordPress
- Slow down for critical steps
- Show real-time notifications
- Include error handling (if applicable)

---

#### 5. Using AI Features for Writing (10-12 minutes)

**Title:** "Let's Blog Server: AI-Powered Writing Assistance"

**Content:**

1. **What AI can do** (2 min)
   - Draft generation from outlines
   - Proofreading suggestions
   - Tag generation
   - Show example outputs

2. **Generating draft from outline** (4 min)
   - Start with simple outline
   - Use AI to generate initial draft
   - Show selection menu
   - Show generated content
   - Demonstrate iteration (regenerate, adjust)

3. **Proofreading with AI** (3 min)
   - Select paragraph
   - Invoke proofread function
   - Review suggestions
   - Show acceptance/rejection workflow
   - Review before/after

4. **Tag suggestion** (2 min)
   - Request tag suggestions
   - Review generated tags
   - Add/remove tags
   - Show final set

5. **Tips and limitations** (1 min)
   - Always review AI-generated content
   - Use as starting point, not final content
   - Best practices for prompting

**Graphics Needed:**
- Animated menu selections
- Highlighting of suggested text
- Before/after comparison

---

#### 6. Generating Featured Images (8-10 minutes)

**Title:** "Let's Blog Server: AI Image Generation for Featured Images"

**Content:**

1. **Understanding image generation** (2 min)
   - What ComfyUI does
   - Supported image formats/styles
   - Generation time expectations

2. **Generating image** (4 min)
   - Write article description/title
   - Click "Generate Featured Image"
   - Describe desired image
   - Show generation in progress
   - Display generated options (2-3 variations)
   - Preview each option

3. **Selecting and using image** (2 min)
   - Choose preferred image
   - See it applied to article
   - Show how it appears in WordPress

4. **Regenerating/customizing** (2 min)
   - Regenerate with different description
   - Show batch operations
   - Demonstrate variations

**Visual Elements:**
- Progress bar during generation
- Grid layout of image options
- Before/after article appearance

---

### Tier 3: Advanced Features

#### 7. Multi-Site Publishing Campaign (12-15 minutes)

**Title:** "Let's Blog Server: Publishing to Multiple WordPress Sites"

**Content:**

1. **Multi-site setup review** (2 min)
   - List connected sites
   - Show site management interface
   - Verify multiple sites configured

2. **Single article multi-site publish** (5 min)
   - Write article
   - Click Publish
   - Show site selection (checkboxes for each site)
   - Category mapping (if different categories on different sites)
   - Publish to all sites
   - Show progress for each site

3. **Campaign publishing** (5 min)
   - Create campaign
   - Add multiple related articles
   - Select target sites
   - Set publication schedule
   - Batch publish

4. **Monitoring publication** (3 min)
   - View article status
   - Check sync status across sites
   - Verify all sites received article
   - Show error recovery (if applicable)

**Key Visuals:**
- Multi-site selection interface
- Progress indicators for each site
- Status dashboard

---

#### 8. Managing Articles & Revisions (10-12 minutes)

**Title:** "Let's Blog Server: Article Management and Version Control"

**Content:**

1. **Article search and filters** (3 min)
   - Show search interface
   - Demonstrate filters:
     - By status (Draft/Published/Scheduled)
     - By date range
     - By site
     - By tags
   - Show results updating

2. **Batch operations** (4 min)
   - Select multiple articles
   - Demonstrate bulk actions:
     - Add tags
     - Change status
     - Delete
   - Show confirmation dialogs
   - Execute operation

3. **Article revisions** (3 min)
   - View article history
   - Show timeline of changes
   - Demonstrate diff view
   - Revert to earlier version

4. **Syncing updates** (2 min)
   - Modify published article
   - Update on WordPress
   - Show sync status

---

### Tier 4: Troubleshooting & Optimization

#### 9. Troubleshooting Common Issues (15-20 minutes)

**Title:** "Let's Blog Server: Troubleshooting Guide and Solutions"

**Content:**

1. **Connection issues** (5 min)
   - Cannot access https://localhost
   - Solution: SSL certificate warning handling
   - Solution: Port conflicts
   - Solution: Docker service not running

2. **VSCode extension issues** (5 min)
   - Extension not connecting
   - Solution: API key verification
   - Solution: URL configuration
   - Solution: SSL certificate trust setup

3. **WordPress publishing fails** (5 min)
   - Authentication errors
   - Network timeouts
   - Solution: Verify the SSH connection settings
   - Solution: Test connection in settings

4. **AI features slow/unavailable** (3 min)
   - GPU not recognized
   - Model not loaded
   - Solution: Check NVIDIA drivers
   - Solution: Monitor container logs

5. **Performance optimization** (2 min)
   - Resource monitoring
   - GPU utilization
   - Database optimization

---

## Video Production Notes

### Technical Setup

- **Screen Resolution:** 1920×1080 (1080p) minimum
- **Recording Tool:** OBS Studio (free), ScreenFlow (macOS), or Camtasia
- **Audio:** Clear microphone, consider background music
- **Color Scheme:** Use light/dark theme matching the application

### Best Practices

1. **Pacing**
   - Slow down for complex steps
   - Pause briefly to let viewers read on-screen text
   - Speed up for routine actions (typing, navigation)

2. **Annotations**
   - Use arrows/circles to highlight important elements
   - Add text overlays for critical information
   - Use fade transitions between major sections

3. **Error Handling**
   - Show common errors and solutions
   - Demonstrate recovery procedures
   - Don't edit out mistakes; explain how to fix them

4. **Audio Quality**
   - Speak clearly, not too fast
   - Use consistent volume
   - Avoid background noise
   - Consider subtitles for accessibility

### Publishing Recommendations

- **Platform:** YouTube (embed in this guide)
- **Visibility:** Public or unlisted (link from documentation)
- **Playlists:** Organize by tier (Essentials, Workflows, Advanced)
- **Description:** Include timestamps and linked chapters
- **Tags:** "blog", "WordPress", "publishing", "tutorial"

## Video Content Template

For each video, include:

```markdown
### [Video Title]

**Duration:** [minutes]
**Difficulty:** [Beginner/Intermediate/Advanced]
**Prerequisites:** [Any prior knowledge needed]

**Topics Covered:**
- Topic 1
- Topic 2
- Topic 3

**Key Takeaways:**
- Learning outcome 1
- Learning outcome 2

**Useful Commands/Shortcuts:**
\`\`\`bash
command here
\`\`\`

**Timestamp Guide:**
- 0:00 Introduction
- 1:30 First topic
- 3:45 Second topic
```

## Integration with Documentation

- Embed videos in relevant markdown guides
- Link to video in "Getting Started"
- Create "Video Playlist" index page
- Add "Video Tutorial" callouts in written guides

## Planned Video Delivery Timeline

1. **Phase 1 (Essential):** Videos 1-3 (Initial setup focus)
2. **Phase 2 (Core workflows):** Videos 4-6 (Main usage patterns)
3. **Phase 3 (Advanced):** Videos 7-8 (Power users)
4. **Phase 4 (Support):** Video 9 (Troubleshooting)

---

## Creating Demonstration Content

For videos requiring sample content:

- **Demo WordPress Sites:** Set up test sites for publication demos
- **Sample Articles:** Pre-write articles to demonstrate workflows
- **Account Setup:** Use consistent test credentials
- **Images:** Use placeholder images for featured image generation

## Accessibility Considerations

- Add captions to all videos
- Provide transcripts
- Use sufficient color contrast
- Allow video controls (play/pause/skip)
- Host videos with accessible player

---

**Next Steps:**
1. Create storyboards for each video
2. Set up recording equipment and test
3. Record Tier 1 videos first (essentials)
4. Review and refine based on user feedback
5. Plan publication schedule

For detailed written guides, see:
- [Getting Started Guide](GETTING_STARTED.md)
- [Features and Usage](FEATURES_AND_USAGE.md)
- [Article Authoring Best Practices](ARTICLE_AUTHORING_BEST_PRACTICES.md)
- [Comprehensive Troubleshooting](COMPREHENSIVE_TROUBLESHOOTING.md)
