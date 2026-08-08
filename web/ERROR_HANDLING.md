# Error Handling Strategy

This document outlines the error handling strategy for the Let's Blog Server frontend application.

## Overview

The error handling system consists of three layers:
1. **Global Error Boundary** - Catches unhandled errors across the entire application
2. **Page-Level Error Boundaries** - Catches errors within specific pages
3. **Component-Level Error Handling** - Handled within components using try-catch

## Global Error Boundary

The global error boundary (`src/app/error.tsx`) catches all unhandled errors that bubble up from the application. It provides:
- User-friendly error message
- Error ID (digest) for tracking
- Retry button to attempt recovery
- Link back to home page
- Detailed error information in development mode

### Usage

The global error boundary is automatically applied to the entire application through Next.js's error handling mechanism.

## Page-Level Error Boundaries

Page-level error boundaries are implemented at the route segment level:

- `src/app/projects/error.tsx` - Projects page
- `src/app/sites/error.tsx` - Sites page
- `src/app/posts/error.tsx` - Posts page
- `src/app/ai-jobs/error.tsx` - AI Jobs page
- `src/app/audit-logs/error.tsx` - Audit logs page
- `src/app/users/error.tsx` - Users page
- `src/app/system/error.tsx` - System page

Each page-level error boundary provides:
- Contextual error message specific to the page
- Error logging to backend
- Console logging for debugging
- Retry and navigation buttons

### Creating Page-Level Error Boundaries

To add error boundaries to a new page:

1. Create `error.tsx` in the page's directory
2. Implement the error component following the pattern:

```tsx
'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { logErrorToBackend, logErrorToConsole } from '@/lib/errorLogger';

interface ErrorProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function PageNameError({ error, reset }: ErrorProps) {
  useEffect(() => {
    logErrorToConsole('Page name error', error, { page: 'page-name' });
    logErrorToBackend(error, {
      level: 'error',
      context: { page: 'page-name', digest: error.digest },
    });
  }, [error]);

  return (
    // Error UI here
  );
}
```

## Error Logging

### Logger Functions

The error logging system is provided by `src/lib/errorLogger.ts`:

#### `logErrorToBackend(error, options?)`

Sends error information to the backend API for centralized logging.

**Parameters:**
- `error`: Error object or error message string
- `options.level`: Log level ('error' | 'warn') - default: 'error'
- `options.context`: Additional context data
- `options.componentStack`: React component stack trace

**Example:**
```ts
import { logErrorToBackend } from '@/lib/errorLogger';

try {
  // code
} catch (error) {
  logErrorToBackend(error, {
    level: 'error',
    context: { action: 'fetchProjects' },
  });
}
```

#### `logErrorToConsole(message, error?, context?)`

Logs error information to browser console for debugging.

**Parameters:**
- `message`: Error message
- `error`: Error object (optional)
- `context`: Additional context data (optional)

**Example:**
```ts
import { logErrorToConsole } from '@/lib/errorLogger';

logErrorToConsole('Failed to load projects', error, { 
  userId: 123,
  retryAttempt: 1 
});
```

## Error Handling Flow

### 1. Synchronous Errors

When a synchronous error occurs in a component:

1. React catches the error
2. Nearest error boundary catches it
3. Page-level or global error boundary displays fallback UI
4. Error is logged to backend and console

### 2. Asynchronous Errors

For async errors in event handlers or effects:

```ts
async function handleClick() {
  try {
    await fetchData();
  } catch (error) {
    logErrorToBackend(error, {
      context: { action: 'handleClick' }
    });
    // Show user-friendly message
  }
}
```

### 3. Server-Side Errors

For Next.js Server Components, errors bubble up to error boundaries automatically. No additional handling needed.

## Error States and Recovery

### User-Facing Messages

Error messages should be:
- Clear and actionable
- In the user's language (Japanese)
- Free of technical jargon
- Provide recovery options (retry, go home, etc.)

### Recovery Mechanisms

1. **Retry Button** - Resets the error boundary to attempt recovery
2. **Home Link** - Navigates back to the home page
3. **Refresh Page** - User can manually refresh the browser

## Development vs Production

### Development Mode

In development:
- Full error stack traces are displayed
- Component stack information is shown
- Detailed error context is logged

### Production Mode

In production:
- User-friendly error messages are shown
- Error ID (digest) is displayed for support reference
- Technical details are hidden
- Errors are sent to backend for monitoring

## Testing Error Boundaries

Error boundaries can be tested by:

1. Throwing errors in test components
2. Mocking logging functions
3. Verifying error UI is rendered
4. Checking that logging functions are called

See `src/lib/__tests__/errorLogger.test.ts` and `src/app/__tests__/error.test.tsx` for examples.

## Monitoring and Alerts

Errors logged to the backend can be:
- Tracked and analyzed
- Used for alerting on recurring issues
- Investigated using error ID and context information

## Common Error Scenarios

### API Request Failures

```ts
try {
  const data = await fetchProjectsList();
} catch (error) {
  logErrorToBackend(error, {
    context: { action: 'listProjects', endpoint: '/api/projects' }
  });
  throw error; // Let error boundary handle it
}
```

### Data Processing Errors

```ts
try {
  const processed = processData(rawData);
} catch (error) {
  logErrorToBackend(error, {
    context: { action: 'processData', dataType: 'projects' }
  });
  setData([]); // Fallback to empty state
}
```

### Async Component Errors

Server components will automatically bubble errors to error boundaries.

## Best Practices

1. **Always log errors** - Use `logErrorToBackend` for all caught errors
2. **Provide context** - Include relevant information about what was happening
3. **User-friendly messages** - Don't expose technical details to users
4. **Let boundaries handle it** - Don't hide all errors; use error boundaries for proper handling
5. **Test error scenarios** - Include error handling in test suites
6. **Monitor production** - Use backend logging to identify recurring issues
