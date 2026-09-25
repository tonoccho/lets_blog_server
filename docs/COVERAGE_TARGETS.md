# Code Coverage Targets

This document outlines the code coverage targets and thresholds for each module in the Let's Blog Server project.

## Overview

Code coverage is measured across multiple dimensions:
- **Statements**: Individual executable statements
- **Branches**: All conditional branches (if/else, switch cases)
- **Functions**: All function/method definitions
- **Lines**: Individual lines of code

## Coverage Targets by Module

Since #557/#587, the backend is a Gradle multi-project build. Each module is tested and
covered independently. Coverage is measured locally — there is no CI (#1027), and the
Codecov upload that used to carry a per-module `flags:` tag no longer runs.
None of these modules currently define a `jacocoTestCoverageVerification` threshold in their
`build.gradle` (verified 2026-08) — the numbers below are targets tracked via Codecov/PR
review, not a build-breaking gate. The same threshold values that applied to the pre-split
single project are kept here, applied uniformly to every backend module, since no
module-specific evidence for different numbers exists yet.

### `libs:lbs-common`

**Location**: `packages/lbs-common/build/reports/jacoco/test/jacocoTestReport.xml`

**Target Thresholds**: Statements 60% / Branches 50% / Functions 60% / Lines 60%

No `**/config/**`-style exclusions are configured for this module's `jacocoTestReport` (see
`packages/lbs-common/build.gradle`); it holds only cross-cutting utilities, not domain logic
(#554), so the general thresholds apply as-is. Test fixtures (`src/testFixtures/`, e.g.
`com.letsblog.common.testfixtures.JwtTestFixtures`, see
[ADR-0006](adr/0006-per-service-test-strategy.md)) are not part of `src/main` and are not
subject to these coverage targets.

### ドメインサービス9つ

`services:identity` / `project` / `content` / `media` / `ai` / `analytics` / `publishing` /
`platform` / `log-writer`。

**Location**: `services/<name>/build/reports/jacoco/test/jacocoTestReport.xml`

**Target Thresholds**: Statements 60% / Branches 50% / Functions 60% / Lines 60%

JaCoCo の除外設定は現時点でどのサービスにも入れていない。分割前の `legacy-api` は
`**/config/**` / `**/entity/**` / `**/dto/**` / `**/exception/**` / `**/*$*` を除外していたが、
そのサービスは #583 で削除された。除外を入れるかどうかは、モジュールごとに実測してから決める。

**優先して上げたいパッケージ**(モジュール名は `com.letsblog.<service>`):

- `service.*` — ビジネスロジック(目標 80%+)
- `controller.*` — RESTエンドポイント(目標 75%+)
- `repository.*` — データアクセス(目標 70%+)

### `services:identity`

**Location**: `services/identity/build/reports/jacoco/test/jacocoTestReport.xml`

**Target Thresholds**: Statements 60% / Branches 50% / Functions 60% / Lines 60%

No JaCoCo exclusions are currently configured in `services/identity/build.gradle`; the general
thresholds apply to the whole module until package-specific guidance is established.

### `services:log-writer`

**Location**: `services/log-writer/build/reports/jacoco/test/jacocoTestReport.xml`

**Target Thresholds**: Statements 60% / Branches 50% / Functions 60% / Lines 60%

### `services:gateway`

**Location**: `services/gateway/build/reports/jacoco/test/jacocoTestReport.xml`

**Target Thresholds**: Statements 60% / Branches 50% / Functions 60% / Lines 60%

### Frontend (TypeScript/React)

**Location**: `apps/web/coverage/coverage-final.json`

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
- `src/lib/` - Utility functions (target: 75%+)
- `src/app/` - Page components/routes (target: 50%+)

(Verified against `apps/web/src/` 2026-08: no dedicated `src/hooks/` directory currently exists;
removed from this list. Reintroduce it here if one is added.)

### VSCode拡張 (`apps/extension`)

**Location**: `apps/extension/coverage/` (`npm --prefix apps/extension run test:coverage`)

**Target Thresholds**(純ロジックのモジュール): Statements 90% / Branches 90% / Functions 90% / Lines 90%

拡張は `apps/web` とは別の jest プロジェクトで、閾値は
[apps/extension/jest.config.js](../apps/extension/jest.config.js) の `coverageThreshold` に
**ファイル単位**で設定する(リポジトリ全体の一律の下限は置かない)。理由は、拡張のソースが
性質の異なる2種類に分かれるためである。

| 区分 | 例 | 目標 | 検証手段 |
| --- | --- | --- | --- |
| 純ロジック(サーバーもVSCode APIも介さない) | `frontMatter` `headingContext` `config` `issueParser` `markdownSources` `cache` `urlPaste` `multipart` `articleScaffold` `webviewPanelBase` `proofreadLogic` `webviewSecurity` `schemas` `jwtClaims` `apiBaseUrl` | **Branches 90%+**(既に100%のものは100%を維持) | 単体テスト `src/__tests__/**` |
| VSCode拡張ホストに依存する層 | `extension.ts`(コマンド登録)、各 `*Panel.ts` の生成部、`*CompletionProvider.ts` | 数値目標を置かない | UI操作は [apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.md](../apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.md)、サーバー契約は Layer 1 受け入れテスト(`apps/extension/e2e/`) |

`apiClient.ts` は「どのURLへ何を送るか」を決める層であり、送信内容そのものは
サーバー越しに観測できない。`src/__tests__/apiClientRequests.test.ts` が
`httpClient` をモックして担保する(issue #942)。

```bash
npm --prefix apps/extension run test:coverage
```

## Continuous Integration Coverage Check

### API Coverage

Coverage reports are generated per backend module by running the tasks below. There is no
CI and no Codecov upload (#1027); read the reports from the local `build/reports/` paths:

```bash
# From the project root, e.g. content
./gradlew :services:content:test :services:content:jacocoTestReport
```

Results are available at:
- Local: `services/<service>/build/reports/jacoco/test/index.html` (or
  `packages/lbs-common/build/reports/jacoco/test/index.html`)
- Codecov: https://codecov.io/gh/tonoccho/lets_blog_server

### Frontend Coverage

Coverage reports are generated and uploaded to Codecov during CI/CD:

```bash
cd apps/web
npm run test:coverage
```

Results are available at:
- Local: `apps/web/coverage/lcov-report/index.html`
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

- [Frontend Tests](../apps/web/jest.config.ts) - Jest configuration
- [identity JaCoCo configuration](../services/identity/build.gradle)
- [lbs-common JaCoCo configuration](../packages/lbs-common/build.gradle)
- [README → 品質の担保](../README.md#品質の担保) - What enforces quality without CI
- [Test Documentation](./TEST_DOCUMENTATION.md) - How to run tests per service, JWT test fixture
- [ADR-0006: サービス別のテスト戦略](./adr/0006-per-service-test-strategy.md)
