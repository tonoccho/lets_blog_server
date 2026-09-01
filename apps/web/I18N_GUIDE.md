# Internationalization (i18n) Guide

## Overview

This project uses **next-intl** for internationalization support. It provides built-in routing, server/client component support, and TypeScript type safety for translations.

## Supported Languages

- **Japanese (ja)** - Default language
- **English (en)**

## Project Structure

```
web/
├── messages/
│   ├── ja.json          # Japanese translations
│   └── en.json          # English translations
├── src/
│   ├── i18n.ts          # i18n configuration
│   ├── lib/
│   │   └── i18nConfig.ts # Locale configuration
│   ├── middleware.ts    # Routing middleware
│   └── app/
│       ├── layout.tsx   # Root layout with locale support
│       ├── LanguageSwitcher.tsx # Language selector UI
│       └── ...
└── next.config.ts       # Next.js configuration with i18n plugin
```

## Adding Translations

### Step 1: Add message keys to translation files

Edit `messages/ja.json` and `messages/en.json`:

```json
{
  "common": {
    "save": "保存",
    "cancel": "キャンセル"
  }
}
```

```json
{
  "common": {
    "save": "Save",
    "cancel": "Cancel"
  }
}
```

### Step 2: Use translations in Server Components

```typescript
import { useTranslations } from "next-intl";

export default function MyComponent() {
  const t = useTranslations("common");
  
  return <button>{t("save")}</button>;
}
```

### Step 3: Use translations in Client Components

Add the `"use client"` directive and use the same pattern:

```typescript
"use client";

import { useTranslations } from "next-intl";

export function MyButton() {
  const t = useTranslations("common");
  
  return <button>{t("save")}</button>;
}
```

## Language Switching

The language switcher is available in the header next to the theme switcher. Users can select between Japanese and English.

- The selected language is reflected in the URL: `/en/dashboard` for English, `/dashboard` for Japanese (default)
- Language preference persists across navigation within the app
- The middleware automatically handles routing between locales

## URL Structure

- Japanese (default): `/dashboard`, `/sites`, etc.
- English: `/en/dashboard`, `/en/sites`, etc.

## Namespaces

Translations are organized by namespace to keep them organized and maintainable:

- **common**: Shared/generic labels (Save, Cancel, etc.)
- **nav**: Navigation items and labels
- **header**: Header-related text (logout, menu labels)
- **dashboard**: Dashboard-specific text

When using translations, specify the namespace:
```typescript
const t = useTranslations("dashboard");
```

## Best Practices

1. **Organize by feature**: Group related translations under meaningful namespaces
2. **Keep keys descriptive**: Use `openAdminMenu` instead of `btn1`
3. **Avoid hardcoding text**: Replace all user-visible strings with translation keys
4. **Extract early**: Add translations as you add features, not after
5. **Test both languages**: Always verify translations work in both languages

## Adding New Languages

To add a new language (e.g., Spanish):

1. Create `messages/es.json` with all translation keys
2. Update `src/lib/i18nConfig.ts`:
   ```typescript
   export const locales = ['ja', 'en', 'es'] as const;
   ```
3. Update `LanguageSwitcher.tsx` to include the new language option

## Common Patterns

### Dynamic content with translations

```typescript
const t = useTranslations("dashboard");

const cards = [
  { label: t("registeredSites"), value: sites.length },
  { label: t("posts"), value: posts.length },
];
```

### Conditional translations

```typescript
const t = useTranslations("messages");

const message = isError ? t("errorOccurred") : t("successMessage");
```

### Plural handling (if needed in future)

next-intl supports pluralization through message formatting. See [next-intl documentation](https://next-intl-docs.vercel.app/) for details.

## Troubleshooting

### Messages not loading?

1. Check that locale is correctly set in `i18nConfig.ts`
2. Ensure JSON files are valid (no trailing commas)
3. Verify component imports `useTranslations` from `next-intl`

### Language switcher not working?

1. Ensure `LanguageSwitcher.tsx` is correctly imported in layout
2. Check that middleware is configured in `middleware.ts`
3. Verify Next.js build completes without errors

### TypeScript errors?

If TypeScript complains about missing message keys, ensure:
1. The namespace exists in translation files
2. The key is spelled correctly
3. Keys are added to both `ja.json` and `en.json`
