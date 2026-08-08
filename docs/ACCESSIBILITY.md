# Accessibility (a11y) Testing and Standards

## Overview

This document outlines the accessibility standards and practices for the Let's Blog application. We follow the Web Content Accessibility Guidelines (WCAG) 2.1 at Level AA compliance to ensure our application is usable by everyone, including people with disabilities.

## WCAG 2.1 Level AA Standards

### The Four Principles: POUR

Accessible web content must be:

1. **Perceivable** - Users must be able to perceive the information presented
2. **Operable** - Users must be able to operate the interface
3. **Understandable** - Users must be able to understand the information and how to operate the interface
4. **Robust** - Content must be compatible with current and future assistive technologies

### Key WCAG 2.1 Level AA Requirements

#### 1. Perceivable

- **Alternative Text for Images (1.1.1)**
  - All images must have appropriate alt text
  - Alt text should be concise and describe the purpose or content of the image
  - Decorative images should have empty alt text (`alt=""`)

- **Color Not Sole Means (1.4.1)**
  - Color must not be the only way to convey information
  - Use text labels, icons, or patterns in addition to color

- **Color Contrast (1.4.3)**
  - Text and background colors must have sufficient contrast
  - Minimum contrast ratios:
    - Level AA: 4.5:1 for normal text, 3:1 for large text
    - Level AAA: 7:1 for normal text, 4.5:1 for large text

- **Text Spacing (1.4.12)**
  - Users should be able to adjust text spacing (line height, letter spacing, etc.)
  - Application should remain functional when text spacing is increased

#### 2. Operable

- **Keyboard Accessible (2.1.1)**
  - All functionality must be available via keyboard
  - Focus must be visible at all times
  - No keyboard traps (users can navigate away from any element)

- **Focus Visible (2.4.7)**
  - Focus indicator must be visible when elements receive focus
  - Focus should not be removed without providing an alternative

- **Focus Order (2.4.3)**
  - Elements must receive focus in a logical order
  - Tab order should follow visual flow and logical grouping

- **Heading Hierarchy (2.4.1)**
  - Headings must be used to structure content
  - Heading hierarchy should start with H1 and not skip levels
  - Headings should accurately describe section content

#### 3. Understandable

- **Form Labels (3.3.2)**
  - Form inputs must have associated labels
  - Labels should be descriptive and nearby inputs

- **ARIA Attributes**
  - Use ARIA (Accessible Rich Internet Applications) attributes to enhance semantic meaning
  - `aria-label`: Provides an accessible name for elements without visible text
  - `aria-describedby`: Provides additional description for form inputs
  - `aria-live`: Announces dynamic content changes to screen readers
  - `aria-expanded`: Indicates whether expandable elements are open or closed

- **Error Messages (3.3.1)**
  - Error messages must be clear and suggest corrections
  - Errors should be associated with their form fields

#### 4. Robust

- **Semantic HTML**
  - Use proper HTML elements (buttons instead of divs for buttons, etc.)
  - Use semantic elements like `<nav>`, `<main>`, `<article>`, etc.

- **ARIA Usage**
  - Use ARIA to enhance rather than replace semantic HTML
  - Ensure ARIA attributes are used correctly

## Accessibility Testing Pipeline

### Automated Testing with axe-core

The application includes automated accessibility testing using `axe-playwright` in the E2E test suite.

**Running Accessibility Tests:**

```bash
npm run test:e2e -- --grep "Accessibility"
```

**Test Coverage:**
- Home page accessibility scan
- Form accessibility (labels, ARIA attributes)
- Keyboard navigation
- Heading hierarchy
- Image alt text verification
- Link text verification
- Focus indicator visibility

### Manual Testing Checklist

In addition to automated tests, manual testing should be performed to verify:

- [ ] **Keyboard Navigation**
  - [ ] Can navigate entire page using Tab key
  - [ ] Tab order is logical and follows visual flow
  - [ ] Focus is always visible
  - [ ] No keyboard traps

- [ ] **Screen Reader Testing**
  - [ ] Page structure is logical when read aloud
  - [ ] All form fields have labels
  - [ ] Images have appropriate alt text
  - [ ] Links have descriptive text
  - [ ] Dynamic content is announced

- [ ] **Visual Accessibility**
  - [ ] Color contrast is sufficient (use WebAIM Contrast Checker)
  - [ ] Text is readable at 200% zoom
  - [ ] Text spacing can be increased without loss of functionality
  - [ ] Focus indicators are clearly visible

- [ ] **Forms**
  - [ ] All inputs have associated labels
  - [ ] Required fields are marked
  - [ ] Error messages are clear and associated with fields
  - [ ] Help text is provided where needed

- [ ] **Navigation**
  - [ ] Skip links are present (skip to main content)
  - [ ] Navigation structure is logical
  - [ ] Current page is indicated in navigation

### Screen Readers to Test With

- **Windows**: NVDA (free), JAWS (commercial)
- **macOS**: VoiceOver (built-in)
- **iOS**: VoiceOver (built-in)
- **Android**: TalkBack (built-in)

## React Component Accessibility Guidelines

### Button Components

```tsx
// ✓ Good: Semantic button with clear text
<button type="button" onClick={handleClick}>
  ログアウト
</button>

// ✗ Bad: Div masquerading as button
<div onClick={handleClick}>Log Out</div>

// ✓ Good: Icon button with aria-label
<button type="button" aria-label="Menu" onClick={handleClick}>
  <MenuIcon />
</button>
```

### Form Inputs

```tsx
// ✓ Good: Label associated with input
<label htmlFor="email">
  メールアドレス
</label>
<input id="email" name="email" type="email" required />

// ✓ Good: ARIA attributes for complex inputs
<input
  id="password"
  name="password"
  type="password"
  aria-label="Password"
  aria-describedby="password-help"
/>
<p id="password-help">
  8 characters minimum
</p>

// ✗ Bad: Placeholder as label
<input placeholder="Email address" />
```

### Links

```tsx
// ✓ Good: Descriptive link text
<a href="/blog">最新のブログ投稿を読む</a>

// ✗ Bad: Generic link text
<a href="/blog">ここをクリック</a>

// ✓ Good: Icon link with aria-label
<a href="/home" aria-label="Home">
  <HomeIcon />
</a>
```

### Images

```tsx
// ✓ Good: Descriptive alt text
<img src="blog-header.jpg" alt="Blog post about React accessibility" />

// ✓ Good: Decorative image with empty alt
<img src="decorative-border.png" alt="" />

// ✗ Bad: Missing alt text
<img src="blog-header.jpg" />

// ✗ Bad: Redundant alt text
<img src="user-avatar.jpg" alt="Avatar of John" />
<p>John Smith</p>
```

### Lists

```tsx
// ✓ Good: Semantic list structure
<ul>
  <li>First item</li>
  <li>Second item</li>
</ul>

// ✗ Bad: DIV instead of list
<div>
  <div>First item</div>
  <div>Second item</div>
</div>
```

### ARIA Live Regions

```tsx
// ✓ Good: Announce dynamic content
<div
  role="status"
  aria-live="polite"
  aria-atomic="true"
>
  {message}
</div>

// For urgent alerts
<div
  role="alert"
  aria-live="assertive"
>
  {errorMessage}
</div>
```

## Tools for Accessibility Testing

### Browser Extensions
- **axe DevTools**: Automated accessibility scanning and reporting
- **WAVE**: Accessibility checker for visual feedback
- **Color Contrast Analyzer**: Check color contrast ratios

### Online Tools
- **WebAIM Contrast Checker**: Verify color contrast ratios
- **WAVE Web Accessibility Tool**: Online accessibility checker
- **axe Color Contrast Analyzer**: Check contrast directly in browser

### Command Line
- **axe DevTools CLI**: Automated scanning from terminal
- **pa11y**: Accessibility testing from command line

```bash
# Run pa11y on a URL
pa11y https://localhost:3000

# Generate JSON report
pa11y --reporter json https://localhost:3000 > report.json
```

## Known Accessibility Issues and Fixes

This section tracks identified accessibility issues and their status.

### Issue Tracking Template

```markdown
### [Issue Name]
- **Severity**: Critical / High / Medium / Low
- **WCAG Criterion**: [e.g., 1.4.3 - Color Contrast]
- **Description**: [Description of issue]
- **Fix**: [Implementation approach]
- **Status**: Open / In Progress / Resolved
- **PR**: [Link to PR if applicable]
```

## Resources

### WCAG Documentation
- [WCAG 2.1 Official Guidelines](https://www.w3.org/WAI/WCAG21/quickref/)
- [WCAG 2.1 Easy Checks](https://www.w3.org/WAI/test-evaluate/easy-checks/)
- [Understanding WCAG 2.1](https://www.w3.org/WAI/WCAG21/Understanding/)

### Accessibility Best Practices
- [Web Accessibility by Google](https://www.google.com/accessibility/)
- [Mozilla Accessibility](https://developer.mozilla.org/en-US/docs/Web/Accessibility)
- [A11y Project](https://www.a11yproject.com/)

### Tools and Extensions
- [axe DevTools](https://www.deque.com/axe/devtools/)
- [WAVE Extension](https://wave.webaim.org/extension/)
- [WebAIM](https://webaim.org/)

### Courses and Tutorials
- [Accessible Web Design on Udacity](https://www.udacity.com/course/web-accessibility--ud891)
- [Accessibility in JavaScript Applications](https://egghead.io/courses/develop-accessible-web-apps-with-react)
- [A11y Nutrition Cards](https://www.a11yproject.com/cards/)

## Continuous Improvement

Accessibility is an ongoing process. We commit to:

1. **Regular Testing**: Run automated and manual accessibility tests regularly
2. **Issue Tracking**: Track and prioritize accessibility issues
3. **Training**: Keep team members updated on accessibility best practices
4. **User Feedback**: Collect feedback from users with disabilities
5. **Standards Compliance**: Keep up with WCAG updates and new standards

## Contact and Questions

For questions about accessibility or to report issues, please:
1. Open an issue on GitHub with the `accessibility` label
2. Contact the team via the discussion board
3. Reach out to the project maintainers

---

Last Updated: 2026-08-08
Status: Draft
Compliance Target: WCAG 2.1 Level AA
