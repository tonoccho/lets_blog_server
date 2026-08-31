# Code Coverage Targets

This document outlines the code coverage targets and thresholds for each module in the Let's Blog Server project.

## Overview

Code coverage is measured across multiple dimensions:
- **Statements**: Individual executable statements
- **Branches**: All conditional branches (if/else, switch cases)
- **Functions**: All function/method definitions
- **Lines**: Individual lines of code

## Coverage Targets by Module

### API (Java/Spring Boot)

**Location**: `api/build/reports/jacoco/test/jacocoTestReport.xml`

**Target Thresholds**:
- Statements: 60%
- Branches: 50%
- Functions: 60%
- Lines: 60%

**Excluded from Coverage**:
- Configuration classes (`**/config/**`)
- Entity classes (`**/entity/**`)
- DTOs (`**/dto/**`)
- Exception classes (`**/exception/**`)
- Auto-generated classes (`**/*$*`)

**Core Modules to Prioritize**:
- `com.letsblog.api.service.*` - Business logic (target: 80%+)
- `com.letsblog.api.controller.*` - REST endpoints (target: 75%+)
- `com.letsblog.api.repository.*` - Data access (target: 70%+)
- `com.letsblog.api.util.*` - Utility functions (target: 85%+)

### Frontend (TypeScript/React)

**Location**: `web/coverage/coverage-final.json`

**Target Thresholds**:
- Statements: 40%
- Branches: 40%
- Functions: 40%
- Lines: 40%

**Included in Coverage**:
- `src/**/*.{js,jsx,ts,tsx}`

**Excluded from Coverage**:
- TypeScript declaration files (`*.d.ts`)
- Storybook stories (`*.stories.*`)
- Index files (`index.*`)
- E2E tests (`/e2e/`)

**Priority Areas**:
- `src/components/` - React components (target: 60%+)
- `src/hooks/` - Custom hooks (target: 70%+)
- `src/lib/` - Utility functions (target: 75%+)
- `src/app/` - Page components (target: 50%+)

## Continuous Integration Coverage Check

### API Coverage

Coverage reports are generated and uploaded to Codecov during CI/CD:

```bash
cd api
./gradlew test jacocoTestReport
```

Results are available at:
- Local: `api/build/reports/jacoco/test/index.html`
- Codecov: https://codecov.io/gh/tonoccho/lets_blog_server

### Frontend Coverage

Coverage reports are generated and uploaded to Codecov during CI/CD:

```bash
cd web
npm run test:coverage
```

Results are available at:
- Local: `web/coverage/lcov-report/index.html`
- Codecov: https://codecov.io/gh/tonoccho/lets_blog_server

## Improving Coverage

### For API Development

1. **Write Unit Tests**: Test individual service methods with various inputs
2. **Test Edge Cases**: Cover boundary conditions and error scenarios
3. **Integration Tests**: Test database interactions and service coordination
4. **Avoid Excluding Code**: Only exclude auto-generated or framework boilerplate

Example:
```java
@Test
void testBusinessLogicWithValidInput() {
    // Arrange
    // Act
    // Assert
}

@Test
void testBusinessLogicWithInvalidInput() {
    // Test error handling
}
```

### For Frontend Development

1. **Component Tests**: Test props, state changes, and user interactions
2. **Hook Tests**: Use `renderHook` to test custom React hooks
3. **Util Tests**: Test utility functions with various inputs
4. **E2E Coverage**: Use Playwright for full user flow testing (complements unit coverage)

Example:
```typescript
describe('MyComponent', () => {
  it('renders with provided props', () => {
    render(<MyComponent prop="value" />);
    expect(screen.getByText('value')).toBeInTheDocument();
  });

  it('handles user interactions', () => {
    render(<MyComponent />);
    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByRole('dialog')).toBeVisible();
  });
});
```

## Coverage Monitoring

- **Pull Requests**: Coverage reports are attached to PR artifacts
- **Main/Develop Branches**: Coverage trends are tracked in Codecov
- **Badge Status**: Coverage badges in README reflect latest develop branch coverage

## Related Documentation

- [Frontend Tests](../web/jest.config.ts) - Jest configuration
- [API Tests](../api/build.gradle) - JaCoCo configuration
- [CI/CD Workflows](../.github/workflows/) - Automated testing and coverage
