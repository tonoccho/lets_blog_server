# Accessibility Checklist for Developers

Use this checklist when developing new features or making changes to the application.

## Before Submitting a PR

### Semantic HTML
- [ ] All buttons use `<button>` elements (not `<div>` with onClick)
- [ ] All links use `<a>` elements
- [ ] Form inputs use `<input>`, `<textarea>`, `<select>` elements
- [ ] Lists use `<ul>`, `<ol>`, `<li>` elements
- [ ] Headings use proper hierarchy (H1-H6) without skipping levels
- [ ] Content uses semantic elements (`<nav>`, `<main>`, `<article>`, `<section>`)

### Keyboard Navigation
- [ ] All interactive elements are keyboard accessible (Tab/Enter/Space)
- [ ] Tab order is logical and follows visual flow
- [ ] Focus indicators are visible and clear
- [ ] No keyboard traps (user can navigate away from any element)

### Images and Media
- [ ] All images have alt text (or empty alt if decorative)
- [ ] Alt text is concise and describes the image purpose
- [ ] Decorative images have empty alt: `alt=""`
- [ ] Images with text have alt text that includes the text content
- [ ] SVGs have proper accessibility attributes

### Forms
- [ ] All form inputs have associated `<label>` elements
- [ ] Labels use `htmlFor` attribute matching input `id`
- [ ] Required fields are marked (`required` attribute or `aria-required="true"`)
- [ ] Error messages are clear and associated with fields
- [ ] Form instructions are provided where needed

### Links
- [ ] All links have descriptive text (not "click here" or "read more")
- [ ] Icon-only links have `aria-label` attribute
- [ ] Links that open in new window have indication (icon or text)
- [ ] Links skip words are present if needed

### ARIA Usage
- [ ] ARIA attributes are used to enhance, not replace, semantic HTML
- [ ] `aria-label` is used for icon-only buttons or images
- [ ] `aria-describedby` is used for additional descriptions
- [ ] `aria-live` is used for dynamic content updates
- [ ] `aria-expanded` is used for collapsible elements
- [ ] `role` attributes are used correctly when semantic HTML isn't available

### Color and Contrast
- [ ] Text has sufficient contrast with background (4.5:1 minimum)
- [ ] Color is not the only way to convey information
- [ ] Information conveyed by color is also indicated by text/icons

### Text and Typography
- [ ] Text is readable (avoid small font sizes)
- [ ] Line height is adequate (1.5 or more)
- [ ] Text can be resized to 200% without breaking layout
- [ ] Text alignment is left-aligned (or logical for right-to-left languages)

### Focus Management
- [ ] Focus is visible at all times
- [ ] Focus order matches visual order
- [ ] Modals trap focus within the modal
- [ ] When modal closes, focus returns to trigger element
- [ ] Skip links are provided for long pages

### Interactive Components
- [ ] Buttons have clear labels or aria-labels
- [ ] Toggle buttons indicate their state
- [ ] Dropdown menus are keyboard accessible
- [ ] Tooltips are accessible via keyboard
- [ ] Modal dialogs have proper focus management
- [ ] Carousels have keyboard controls

### Video and Audio
- [ ] Videos have captions or transcripts
- [ ] Audio has transcripts
- [ ] Controls are keyboard accessible
- [ ] Autoplay is disabled (or users can pause)

## Testing Process

### Automated Testing
1. Run accessibility tests:
   ```bash
   npm run test:e2e -- --grep "Accessibility"
   ```
2. Review test results and fix any violations
3. Verify no new issues are introduced

### Manual Testing

#### Keyboard Navigation
1. Open page in browser
2. Press Tab to navigate through all interactive elements
3. Verify:
   - [ ] All interactive elements are reachable
   - [ ] Tab order is logical
   - [ ] Focus is always visible
   - [ ] No keyboard traps

#### Screen Reader (NVDA/JAWS on Windows, VoiceOver on Mac)
1. Enable screen reader
2. Navigate through page
3. Verify:
   - [ ] Page structure is logical
   - [ ] Form fields have labels
   - [ ] Images have alt text
   - [ ] Links have descriptive text
   - [ ] Buttons have clear labels

#### Color Contrast
1. Use WebAIM Contrast Checker or axe DevTools
2. Verify text/background color contrast is at least 4.5:1
3. Check both normal and large text

#### Zoom Testing
1. Zoom page to 200%
2. Verify:
   - [ ] All content is readable
   - [ ] Layout doesn't break
   - [ ] No horizontal scrolling required

## Common Accessibility Mistakes to Avoid

❌ **Don't:**
- Use color alone to convey information
- Remove focus indicators
- Use `<div>` and `<span>` for buttons/links
- Use placeholder as label
- Autoplay videos/audio
- Create keyboard traps
- Use images of text
- Skip heading levels
- Make interactive elements too small

✅ **Do:**
- Use semantic HTML
- Provide adequate color contrast
- Make focus indicators visible
- Use proper labels for form inputs
- Allow keyboard navigation
- Provide alt text for images
- Use ARIA when needed
- Test with real assistive technology
- Follow WCAG guidelines

## Resources

- **WCAG 2.1 Quick Reference**: https://www.w3.org/WAI/WCAG21/quickref/
- **MDN Accessibility**: https://developer.mozilla.org/en-US/docs/Web/Accessibility
- **A11y Project**: https://www.a11yproject.com/
- **WebAIM**: https://webaim.org/

## Need Help?

1. Check the main [ACCESSIBILITY.md](../docs/ACCESSIBILITY.md) documentation
2. Use axe DevTools browser extension for quick checks
3. Run automated tests: `npm run test:e2e -- --grep "Accessibility"`
4. Ask team members or open an issue with the `accessibility` label

---

**Last Updated**: 2026-08-08
**Compliance Target**: WCAG 2.1 Level AA
