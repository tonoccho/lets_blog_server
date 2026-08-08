# Article Authoring Best Practices

This guide provides best practices for writing quality articles with Let's Blog Server and optimizing them for multi-site publishing.

## Table of Contents

- [Planning Your Article](#planning-your-article)
- [Writing Structure](#writing-structure)
- [Metadata Best Practices](#metadata-best-practices)
- [Content Optimization](#content-optimization)
- [Multi-site Considerations](#multi-site-considerations)
- [Using AI Features Effectively](#using-ai-features-effectively)
- [Publishing Workflow](#publishing-workflow)

## Planning Your Article

### Before You Start Writing

**Definition of Purpose:**
Before writing, clarify:
- **Target audience** - Who are you writing for? (beginners, experts, general audience)
- **Main goal** - What should readers learn/do after reading?
- **Key message** - What's the single most important point?
- **Estimated length** - Blog post (500-2000 words), Tutorial (2000-5000 words), Guide (5000+ words)

### Content Type Patterns

#### Blog Post (500-2000 words)
- **Structure:** Introduction → 2-3 main points → Conclusion
- **Purpose:** Inform, entertain, or quick how-to
- **Time to read:** 3-8 minutes
- **Example:** "5 Docker Security Tips", "New Feature Announcement"

#### Tutorial (2000-4000 words)
- **Structure:** Overview → Prerequisites → Step-by-step instructions → Verification → Troubleshooting
- **Purpose:** Teach readers to accomplish specific task
- **Includes:** Code examples, screenshots, expected outputs
- **Example:** "Building a Node.js API with Let's Blog", "Setting up Docker Compose"

#### Deep Dive / Reference Guide (4000+ words)
- **Structure:** Overview → Background → Detailed sections → Comparison → Advanced topics → Summary
- **Purpose:** Comprehensive coverage of topic
- **Includes:** Concepts, code, examples, visual diagrams
- **Example:** "Complete Docker Networking Guide", "PostgreSQL Performance Tuning Handbook"

### Creating an Outline

Start with outline before writing:

```markdown
# Article Title: [Main Topic]

## 1. Introduction
- Hook/why this matters
- What you'll learn
- Prerequisites (if applicable)

## 2. Main Concept/Section
- Key points
- Examples
- Important considerations

## 3. Next Section
...

## Conclusion
- Summary
- Next steps
- Call to action
```

**Pro Tip:** Use Let's Blog's AI outline generation feature:
- Describe topic
- Let AI generate structure
- Refine based on your expertise

## Writing Structure

### Article Header (Metadata)

Every article needs proper metadata (YAML front matter):

```yaml
---
title: "Docker Security Best Practices: A Complete Guide"
description: "Essential security measures for Docker containers in production environments"
tags:
  - docker
  - security
  - devops
  - containers
date: 2026-08-08
featured_image: ""
---
```

**Best Practices:**

| Field | Best Practice | Example |
|-------|---------------|---------|
| **title** | Clear, descriptive, includes keywords | "Docker Security Best Practices" |
| **description** | Under 160 chars, compelling, includes main keyword | "Essential security measures for Docker containers..." |
| **tags** | 3-5 relevant tags, lowercase, hyphenated | `docker`, `security`, `devops` |
| **date** | ISO format (YYYY-MM-DD) | `2026-08-08` |
| **featured_image** | Leave empty for AI generation, or URL | Use generation feature |

### Opening Paragraph

Your opening should:
1. **Hook the reader** - Why should they care?
2. **Set expectations** - What will they learn?
3. **Be concise** - 2-3 sentences maximum

**❌ Bad Opening:**
"Docker is a containerization technology that many developers use."

**✅ Good Opening:**
"Container security breaches have increased 40% in the past year. In this guide, you'll learn the 5 critical Docker security practices that protect 95% of containerization vulnerabilities."

### Section Headers

Use descriptive headers that work as standalone summary:

```markdown
# How to Write Better Code
## Understanding Code Style
### Why naming conventions matter
```

**Guidelines:**
- Use H2 (`##`) for main sections
- Use H3 (`###`) for subsections
- Avoid vague headers like "Overview" or "Introduction"
- Headers should be scannable and informative

### Paragraph Structure

Write scannable content:

```markdown
**Key Point:** One powerful sentence.

Supporting context: Explain why this matters. Include relevant background 
if readers might be unfamiliar with the concept.

**Action or Example:** Show practical application.

- Bullet point 1
- Bullet point 2
- Bullet point 3
```

**Length:** Keep paragraphs to 3-5 sentences. Short paragraphs are easier to scan on mobile.

### Code Examples

Format code clearly with proper syntax highlighting:

````markdown
```bash
# Good: Language specified
docker run -it --rm ubuntu:latest /bin/bash
```

```python
# Language helps syntax highlighting
def deploy(service_name):
    """Deploy service to production."""
    return deploy_service(service_name)
```

```jsx
// Code examples should be realistic and complete
import { useEffect, useState } from 'react';

export function BlogPost({ id }) {
  const [post, setPost] = useState(null);
  
  useEffect(() => {
    fetch(`/api/posts/${id}`)
      .then(r => r.json())
      .then(setPost);
  }, [id]);
  
  return post ? <article>{post.content}</article> : <div>Loading...</div>;
}
```
````

**Code Best Practices:**
- Show realistic, working code (not pseudo-code)
- Include output/results if relevant
- Keep code blocks under 40 lines (break longer examples)
- Comment tricky sections, not obvious parts
- Use language identifiers for syntax highlighting

### Lists and Bullets

Use lists for scannable information:

**Unordered lists** - When order doesn't matter:
```markdown
## Docker Commands
- `docker run` - Create and start a container
- `docker ps` - List running containers
- `docker logs` - View container logs
```

**Ordered lists** - For step-by-step instructions:
```markdown
## Deployment Steps
1. Build the image
2. Tag the image
3. Push to registry
4. Deploy to production
5. Verify and rollback if needed
```

**Key takeaways** - Important points to remember:
```markdown
**Key Points:**
- Lessons learned
- Main insights
- Critical takeaways
```

### Tables

Use tables for comparisons and structured data:

```markdown
| Aspect | Docker | Kubernetes | OpenShift |
|--------|--------|-----------|-----------|
| Learning Curve | Easy | Steep | Moderate |
| Use Case | Single host | Multi-cluster | Enterprise |
| Overhead | Minimal | High | High |
```

## Metadata Best Practices

### Titles

**Good titles:**
- Specific and descriptive
- Include target keyword naturally
- 50-70 characters
- Avoid clickbait

**Examples:**
- ✅ "Docker Security Best Practices for Production"
- ✅ "How to Optimize PostgreSQL Queries: 5 Proven Techniques"
- ✅ "Complete Guide to Kubernetes Networking"

**❌ Bad titles:**
- "Docker" (too vague)
- "DOCKER SECURITY: YOU WON'T BELIEVE #5!!!" (clickbait)
- "Lorem Ipsum Dolor Sit Amet Consectetur Adipiscing Elit Sed Do" (too long)

### Descriptions (Meta Summary)

The description appears in search results and social media:

**Guidelines:**
- 150-160 characters
- Include main keyword
- Compelling, benefit-focused
- Unique per article

**Example:**
"Learn essential Docker security practices to protect containers in production. Covers image scanning, runtime security, and network isolation."

### Tags

Tags help organize content and improve discoverability:

**Guidelines:**
- Use 3-5 tags per article
- Lowercase, hyphenated
- Be specific ("docker-security" not just "security")
- Avoid duplicate tags per article

**Good tag structure:**
- Technology/tool: `docker`, `kubernetes`, `node-js`
- Topic: `security`, `performance`, `testing`
- Level: `beginner`, `intermediate`, `advanced`
- Type: `tutorial`, `guide`, `howto`

## Content Optimization

### SEO Optimization

**Keyword placement:**
- Title: Include main keyword
- First paragraph: Mention keyword naturally
- Subheadings: Use keyword variants
- Conclusion: Reinforce main keyword

**Readability:**
- Average sentence length: 15-20 words
- Keep paragraphs short (2-4 sentences)
- Use active voice
- Avoid jargon or explain technical terms

**Example (Active vs. Passive):**

❌ Passive: "Docker images should be scanned for vulnerabilities"

✅ Active: "Scan Docker images for vulnerabilities before deployment"

### Linking Best Practices

**Internal Links:**
- Link to related articles
- Use descriptive anchor text (not "click here")
- 2-3 internal links per article

```markdown
For more details, see our [Docker security guide](./FEATURES_AND_USAGE.md).
```

**External Links:**
- Link to authoritative sources
- Always verify link still works before publishing
- Open external links in new tab when applicable

```markdown
[Learn more about Docker security](https://docs.docker.com/engine/security/)
{:target="_blank"}
```

### Formatting for Readability

Use formatting purposefully:

```markdown
**Bold** for emphasis of key terms
*Italics* for introducing new concepts
`code` for inline code/commands
[Links] for related resources
```

**Avoid:**
- ALL CAPS (feels like shouting)
- Multiple formatting on same text (`***bold italic***` - hard to read)
- Colored text (use bold/italic instead)

### Visual Elements

**Screenshots:**
- Use alt text for accessibility
- Include captions
- Crop to relevant area
- Keep consistent style

**Diagrams:**
- Use for complex concepts
- Keep simple and clear
- Include text labels
- Add caption explaining diagram

**Pro Tip:** Use Let's Blog's featured image generation for visual appeal.

## Multi-site Considerations

### Content Suitability

**Before publishing to multiple sites, ask:**

1. **Is this content relevant to all audiences?**
   - A Linux tutorial fits all sites
   - Windows-only how-to may fit only one site

2. **Do different sites need different versions?**
   - Rephrase for different technical levels
   - Adjust code examples for different frameworks
   - Update links to site-specific resources

3. **Should categories differ per site?**
   - Some sites might categorize differently
   - Use Let's Blog's category mapping feature

### Category Mapping

When publishing to multiple sites with different category structures:

**Site A categories:**
- Tutorials
- News
- Tips

**Site B categories:**
- How-To Guides
- Updates
- Tricks & Tips

**Mapping strategy:**
- `Tutorials` → Site A "Tutorials", Site B "How-To Guides"
- `News` → Site A "News", Site B "Updates"
- `Tips` → Both "Tips"

Use Let's Blog's category mapping UI to configure this during publishing.

### Scheduling Considerations

**Time Zone Awareness:**
- Consider when your audience is online
- If sites have different audiences, stagger publication
- Monday-Wednesday typically have best engagement

**Coordinated Publishing:**
- Use campaign publishing for simultaneous release
- Ensures consistency across sites
- Good for announcements and launches

## Using AI Features Effectively

### Draft Generation

**When to use AI drafting:**

✅ **Good uses:**
- Starting from outline (AI fills in structure)
- Brainstorming content variations
- Quick summaries or overviews
- Initial bullet points from topic

❌ **Avoid:**
- Entire articles without review
- Technical content without verification
- Claims requiring fact-checking

**Process:**
1. Create detailed outline
2. Request AI draft from each section
3. Review for accuracy and tone
4. Edit and refine
5. Add your expertise and examples

### Proofreading with AI

**What AI catches:**
- Grammar and spelling
- Sentence structure improvements
- Clarity suggestions
- Tone consistency

**What you should verify:**
- Technical accuracy
- Factual correctness
- Consistency with your brand voice
- Code accuracy (if applicable)

### Tag Suggestions

**Use AI tag suggestions when:**
- Starting on a new topic
- You want to discover related concepts
- Finding overlooked keywords

**Review process:**
1. Accept AI suggestions
2. Remove irrelevant tags
3. Add missed tags
4. Verify 3-5 tags total

## Publishing Workflow

### Pre-Publishing Checklist

Before clicking "Publish":

- [ ] **Content**
  - [ ] Title is clear and descriptive
  - [ ] Description (meta summary) is compelling
  - [ ] All sections have meaningful headers
  - [ ] Code examples are accurate
  - [ ] Links work and are relevant
  - [ ] No typos or grammar errors

- [ ] **Metadata**
  - [ ] 3-5 tags are set
  - [ ] Category is appropriate
  - [ ] Featured image is generated or added
  - [ ] Description is under 160 characters

- [ ] **Multi-site**
  - [ ] Content is relevant to all target sites
  - [ ] Category mapping configured (if needed)
  - [ ] Publication date/time is appropriate

- [ ] **Final Review**
  - [ ] Read from start to finish
  - [ ] Check formatting on mobile
  - [ ] Verify code examples run correctly
  - [ ] Ensure consistency with previous articles

### Publishing Options

**Immediate publish:**
- Use for news, urgent information
- Verify you're ready

**Schedule publication:**
- Publish during optimal time for your audience
- Test content in advance
- Set publication time considering time zones

**Save as draft:**
- For content needing review
- When you need to finalize later
- Good for team collaboration

### Post-Publishing

After publishing:

1. **Verify across sites** (for multi-site publish)
   - Visit each site
   - Confirm formatting correct
   - Check images loaded
   - Verify links work

2. **Share appropriately**
   - Social media posts
   - Email newsletters
   - Relevant communities

3. **Monitor engagement**
   - Comments and feedback
   - Traffic analytics
   - Adjust promotion if needed

4. **Plan updates**
   - Note if content needs updates
   - Schedule refresh for outdated content
   - Keep article current

## Common Writing Mistakes to Avoid

| Mistake | Problem | Solution |
|---------|---------|----------|
| Walls of text | Hard to read | Break into shorter paragraphs |
| Vague titles | Low click rate | Be specific and descriptive |
| Passive voice | Unclear responsibility | Use active voice |
| No conclusion | Reader unsure of takeaway | Summarize and suggest next steps |
| Outdated info | Loss of credibility | Verify dates and versions |
| Too technical | Alienates readers | Explain concepts or target skill level |
| No structure | Hard to follow | Use headers and lists |

## Quick Reference: Article Template

```markdown
---
title: "[Title]: [Main Benefit or Question Answered]"
description: "[Why reader should care. 150-160 chars.]"
tags:
  - keyword1
  - keyword2
  - keyword3
date: YYYY-MM-DD
featured_image: ""
---

# [Article Title]

## Introduction
[Hook - why this matters. What will they learn.]

## Main Concept / First Section
[Detailed explanation with examples.]

## Second Section
[Build on first section.]

## Conclusion
[Summarize key points. Suggest next steps.]
```

## Resources

- [Get Started with Let's Blog](GETTING_STARTED.md)
- [Features and Usage Guide](FEATURES_AND_USAGE.md)
- [Video Tutorials](VIDEO_TUTORIALS_GUIDE.md)
- [Troubleshooting Guide](COMPREHENSIVE_TROUBLESHOOTING.md)

---

**Happy writing!** Remember: Clear, helpful, accurate content is always your goal. Let's Blog's tools support your writing, but your expertise and perspective are irreplaceable.
